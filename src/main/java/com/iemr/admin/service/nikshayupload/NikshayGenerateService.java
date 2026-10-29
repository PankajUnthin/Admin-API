/*
* AMRIT – Accessible Medical Records via Integrated Technology
* Integrated EHR (Electronic Health Records) Solution
*
* Copyright (C) "Piramal Swasthya Management and Research Institute"
*
* This file is part of AMRIT.
*
* This program is free software: you can redistribute it and/or modify
* it under the terms of the GNU General Public License as published by
* the Free Software Foundation, either version 3 of the License, or
* (at your option) any later version.
*
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
* GNU General Public License for more details.
*
* You should have received a copy of the GNU General Public License
* along with this program.  If not, see https://www.gnu.org/licenses/.
*/
package com.iemr.admin.service.nikshayupload;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Runs the in-repo Node/Playwright enroll worker (nikshay-enroll/enroll-json.js)
 * and returns the same JSON shape the FLW UI expects.
 */
@Service
public class NikshayGenerateService {
	private static final Logger logger = LoggerFactory.getLogger(NikshayGenerateService.class);

	@Value("${nikshay.enroll.dir:nikshay-enroll}")
	private String enrollDir;

	@Value("${nikshay.enroll.node:node}")
	private String nodeBinary;

	@Value("${nikshay.enroll.timeout-ms:600000}")
	private long timeoutMs;

	private final ObjectMapper objectMapper = new ObjectMapper();

	public Map<String, Object> generateIds(String username, String password, String csv, String env)
			throws Exception {
		if (username == null || username.isBlank() || password == null || password.isBlank()) {
			throw new IllegalArgumentException("username and password are required");
		}
		if (csv == null || csv.isBlank()) {
			throw new IllegalArgumentException("csv is required");
		}
		String portal = (env == null || env.isBlank()) ? "uat" : env.trim();
		if (!"uat".equalsIgnoreCase(portal) && !"production".equalsIgnoreCase(portal)
				&& !"beta".equalsIgnoreCase(portal)) {
			throw new IllegalArgumentException("env must be uat or production");
		}
		if ("beta".equalsIgnoreCase(portal)) {
			portal = "uat";
		}

		Path workDir = Path.of(enrollDir).toAbsolutePath().normalize();
		Path script = workDir.resolve("enroll-json.js");
		if (!Files.isRegularFile(script)) {
			throw new IllegalStateException(
					"Nikshay enroll worker not found at " + script + " — ensure nikshay-enroll is present and npm install was run");
		}

		Path tempDir = Files.createTempDirectory("nikshay-generate-");
		Path csvPath = tempDir.resolve("input.csv");
		try {
			Files.writeString(csvPath, csv, StandardCharsets.UTF_8);

			List<String> command = new ArrayList<>();
			command.add(nodeBinary);
			command.add(script.toString());
			command.add("--csv");
			command.add(csvPath.toString());
			command.add("--env");
			command.add(portal.toLowerCase());

			ProcessBuilder pb = new ProcessBuilder(command);
			pb.directory(workDir.toFile());
			pb.redirectErrorStream(false);
			Map<String, String> procEnv = pb.environment();
			procEnv.put("NIKSHAY_USER", username);
			procEnv.put("NIKSHAY_PASS", password);
			procEnv.put("NIKSHAY_ENV", portal.toLowerCase());

			logger.info("Starting Nikshay enroll worker env={} cwd={}", portal, workDir);
			Process process = pb.start();

			Thread errPump = new Thread(() -> {
				try (BufferedReader err = new BufferedReader(
						new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
					String line;
					while ((line = err.readLine()) != null) {
						logger.info("[nikshay-enroll] {}", line);
					}
				} catch (IOException e) {
					logger.warn("Error reading enroll stderr", e);
				}
			}, "nikshay-enroll-stderr");
			errPump.setDaemon(true);
			errPump.start();

			String stdout;
			try (BufferedReader out = new BufferedReader(
					new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
				stdout = out.lines().collect(Collectors.joining("\n"));
			}

			boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
			if (!finished) {
				process.destroyForcibly();
				throw new IllegalStateException("Nikshay enroll timed out after " + timeoutMs + " ms");
			}
			errPump.join(5_000);

			int exit = process.exitValue();
			if (stdout == null || stdout.isBlank()) {
				throw new IllegalStateException("Nikshay enroll produced no output (exit=" + exit + ")");
			}

			JsonNode root = objectMapper.readTree(stdout);
			if (root.hasNonNull("error") && !root.has("rows")) {
				throw new IllegalStateException(root.get("error").asText());
			}
			if (exit != 0 && root.hasNonNull("error")) {
				throw new IllegalStateException(root.get("error").asText());
			}

			@SuppressWarnings("unchecked")
			Map<String, Object> result = objectMapper.convertValue(root, LinkedHashMap.class);
			return result;
		} finally {
			deleteRecursively(tempDir);
		}
	}

	/** Removes temp enroll dir including worker side-files (e.g. input.api-results.csv). */
	private void deleteRecursively(Path dir) {
		if (dir == null || !Files.exists(dir)) {
			return;
		}
		try (Stream<Path> walk = Files.walk(dir)) {
			walk.sorted(Comparator.reverseOrder()).forEach(path -> {
				try {
					Files.deleteIfExists(path);
				} catch (IOException e) {
					logger.debug("Could not delete {}", path, e);
				}
			});
		} catch (IOException e) {
			logger.debug("Could not clean temp enroll dir {}", dir, e);
		}
	}
}

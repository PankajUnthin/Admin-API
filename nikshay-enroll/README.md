# Nikshay enroll worker (MMU-API)

Playwright + Node worker called by `POST /stopTb/nikshay/generateIds`.

## Setup (on the machine that runs MMU-API)

```bash
cd nikshay-enroll
npm install
```

Requires Node.js and Chromium (installed via Playwright postinstall).

Config in `application.properties`:

```
nikshay.enroll.dir=nikshay-enroll
nikshay.enroll.node=node
nikshay.enroll.timeout-ms=600000
```

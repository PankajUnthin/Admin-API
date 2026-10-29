package com.iemr.admin.repo.nikshay;


import java.util.Collection;
import java.util.List;

import com.iemr.admin.data.nikshay.NikshayDeviceName;
import com.iemr.admin.data.nikshay.NikshayDeviceVillageMapping;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface NikshayDeviceVillageMappingRepository extends JpaRepository<NikshayDeviceVillageMapping, Integer> {

    /** Active village mappings whose device is active and of the given type (NAAT). */
    @Query("""
        select m from NikshayDeviceVillageMapping m, NikshayDeviceConfiguration d
        where  m.deviceDetailId = d.id
          and  m.villageId in :villageIds
          and  m.isActive = true
          and  d.isActive = true
          and  d.deviceName = :deviceName
        order by m.villageId, m.id
        """)
    List<NikshayDeviceVillageMapping> findActiveByVillageIds(@Param("villageIds") Collection<Integer> villageIds,
                                                             @Param("deviceName") NikshayDeviceName deviceName);
}

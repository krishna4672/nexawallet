package com.nexawallet.platform.modules.discovery.repository;

import com.nexawallet.platform.modules.discovery.entity.ServicePointEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Spring Data JPA Repository for ServicePointEntity.
 */
@Repository
public interface ServicePointRepository extends JpaRepository<ServicePointEntity, UUID> {

    @Query("SELECT s FROM ServicePointEntity s WHERE s.active = true AND s.geohash LIKE CONCAT(:prefix, '%')")
    List<ServicePointEntity> findByGeohashPrefix(@Param("prefix") String prefix);

    @Query("SELECT s FROM ServicePointEntity s WHERE s.active = true AND substring(s.geohash, 1, :length) IN :prefixes")
    List<ServicePointEntity> findByGeohashPrefixes(@Param("prefixes") List<String> prefixes, @Param("length") int length);
}

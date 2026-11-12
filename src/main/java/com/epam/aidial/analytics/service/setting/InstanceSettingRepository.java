package com.epam.aidial.analytics.service.setting;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Spring Data JPA repository for {@link InstanceSettingEntity}, keyed by setting name. */
@Repository
public interface InstanceSettingRepository extends JpaRepository<InstanceSettingEntity, String> {
}

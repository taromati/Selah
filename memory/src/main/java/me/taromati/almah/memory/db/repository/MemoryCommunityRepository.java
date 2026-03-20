package me.taromati.almah.memory.db.repository;

import me.taromati.almah.memory.db.entity.MemoryCommunityEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MemoryCommunityRepository extends JpaRepository<MemoryCommunityEntity, String> {
}

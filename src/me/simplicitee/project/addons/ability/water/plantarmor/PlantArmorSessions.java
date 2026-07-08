package me.simplicitee.project.addons.ability.water.plantarmor;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PlantArmorSessions {

	private final ConcurrentHashMap<UUID, PlantArmorSession> sessions = new ConcurrentHashMap<>();

	public PlantArmorSession get(UUID playerId) {
		return sessions.get(playerId);
	}

	public PlantArmorSession putIfAbsent(UUID playerId, PlantArmorSession session) {
		return sessions.putIfAbsent(playerId, session);
	}

	public PlantArmorSession remove(UUID playerId) {
		return sessions.remove(playerId);
	}

	public boolean contains(UUID playerId) {
		return sessions.containsKey(playerId);
	}

	public List<PlantArmorSession> valuesSnapshot() {
		return new ArrayList<>(sessions.values());
	}
}

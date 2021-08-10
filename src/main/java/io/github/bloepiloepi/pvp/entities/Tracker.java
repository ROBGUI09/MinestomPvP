package io.github.bloepiloepi.pvp.entities;

import io.github.bloepiloepi.pvp.damage.combat.CombatManager;
import io.github.bloepiloepi.pvp.food.HungerManager;
import net.minestom.server.MinecraftServer;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.LivingEntity;
import net.minestom.server.entity.Player;
import net.minestom.server.event.*;
import net.minestom.server.event.entity.EntityTickEvent;
import net.minestom.server.event.entity.EntityVelocityEvent;
import net.minestom.server.event.player.*;
import net.minestom.server.event.trait.EntityEvent;
import net.minestom.server.instance.block.Block;
import net.minestom.server.item.Material;
import net.minestom.server.network.packet.server.play.SetCooldownPacket;
import net.minestom.server.utils.Position;
import net.minestom.server.utils.Vector;
import net.minestom.server.utils.time.TimeUnit;

import java.util.*;

public class Tracker {
	public static final Map<UUID, Integer> lastAttackedTicks = new HashMap<>();
	public static final Map<UUID, Integer> invulnerableTime = new HashMap<>();
	public static final Map<UUID, Float> lastDamageTaken = new HashMap<>();
	public static final Map<UUID, HungerManager> hungerManager = new HashMap<>();
	public static final Map<UUID, Map<Material, Long>> cooldownEnd = new HashMap<>();
	public static final Map<UUID, Boolean> falling = new HashMap<>();
	public static final Map<UUID, Position> previousPosition = new HashMap<>();
	public static final Map<UUID, Vector> playerVelocity = new HashMap<>();
	public static final Map<UUID, Entity> spectating = new HashMap<>();
	public static final Map<UUID, Long> itemUseStartTime = new HashMap<>();
	public static final Map<UUID, Player.Hand> itemUseHand = new HashMap<>();
	public static final Map<UUID, Block> lastClimbedBlock = new HashMap<>();
	public static final Map<UUID, CombatManager> combatManager = new HashMap<>();
	public static final Map<UUID, LivingEntity> lastDamagedBy = new HashMap<>();
	public static final Map<UUID, Long> lastDamageTime = new HashMap<>();
	public static final Map<UUID, Long> fireExtinguishTime = new HashMap<>();
	
	public static <K> void increaseInt(Map<K, Integer> map, K key, int amount) {
		map.put(key, map.getOrDefault(key, 0) + amount);
	}
	
	public static <K> void decreaseInt(Map<K, Integer> map, K key, int amount) {
		map.put(key, map.getOrDefault(key, 0) - amount);
	}
	
	public static <K> void increaseFloat(Map<K, Float> map, K key, float amount) {
		map.put(key, map.getOrDefault(key, 0F) + amount);
	}
	
	public static <K> void decreaseFloat(Map<K, Float> map, K key, float amount) {
		map.put(key, map.getOrDefault(key, 0F) - amount);
	}
	
	public static boolean hasCooldown(Player player, Material material) {
		Map<Material, Long> cooldownMap = cooldownEnd.get(player.getUuid());
		
		return cooldownMap.containsKey(material) && cooldownMap.get(material) > System.currentTimeMillis();
	}
	
	public static void setCooldown(Player player, Material material, int durationTicks) {
		cooldownEnd.get(player.getUuid()).put(material, System.currentTimeMillis() + (long) durationTicks * MinecraftServer.TICK_MS);
		onCooldown(player, material, durationTicks);
	}
	
	public static void updateCooldown() {
		if (cooldownEnd.isEmpty()) return;
		long time = System.currentTimeMillis();
		
		cooldownEnd.forEach((uuid, cooldownMap) -> {
			if (cooldownMap.isEmpty()) return;
			
			Iterator<Map.Entry<Material, Long>> iterator = cooldownMap.entrySet().iterator();
			Player player = MinecraftServer.getConnectionManager().getPlayer(uuid);
			assert player != null;
			
			while (iterator.hasNext()) {
				Map.Entry<Material, Long> entry = iterator.next();
				if (entry.getValue() <= time) {
					iterator.remove();
					onCooldown(player, entry.getKey(), 0);
				}
			}
		});
	}
	
	public static void onCooldown(Player player, Material material, int duration) {
		SetCooldownPacket packet = new SetCooldownPacket();
		packet.itemId = material.getId();
		packet.cooldownTicks = duration;
		
		player.getPlayerConnection().sendPacket(packet);
	}
	
	public static void register(EventNode<? super EntityEvent> eventNode) {
		EventNode<EntityEvent> node = EventNode.type("tracker-events", EventFilter.ENTITY);
		eventNode.addChild(node);
		
		node.addListener(PlayerLoginEvent.class, event -> {
			UUID uuid = event.getPlayer().getUuid();
			
			Tracker.lastAttackedTicks.put(uuid, 0);
			Tracker.invulnerableTime.put(uuid, 0);
			Tracker.lastDamageTaken.put(uuid, 0F);
			Tracker.hungerManager.put(uuid, new HungerManager(event.getPlayer()));
			Tracker.cooldownEnd.put(uuid, new HashMap<>());
			Tracker.falling.put(uuid, false);
			Tracker.spectating.put(uuid, event.getPlayer());
			Tracker.combatManager.put(uuid, new CombatManager(event.getPlayer()));
		});
		
		node.addListener(PlayerDisconnectEvent.class, event -> {
			UUID uuid = event.getPlayer().getUuid();
			
			Tracker.lastAttackedTicks.remove(uuid);
			Tracker.invulnerableTime.remove(uuid);
			Tracker.lastDamageTaken.remove(uuid);
			Tracker.hungerManager.remove(uuid);
			Tracker.cooldownEnd.remove(uuid);
			Tracker.falling.remove(uuid);
			Tracker.previousPosition.remove(uuid);
			Tracker.playerVelocity.remove(uuid);
			Tracker.spectating.remove(uuid);
			Tracker.itemUseStartTime.remove(uuid);
			Tracker.itemUseHand.remove(uuid);
			Tracker.lastClimbedBlock.remove(uuid);
			Tracker.combatManager.remove(uuid);
			Tracker.lastDamagedBy.remove(uuid);
			Tracker.lastDamageTime.remove(uuid);
		});
		
		node.addListener(PlayerTickEvent.class, event -> {
			Player player = event.getPlayer();
			
			Tracker.increaseInt(Tracker.lastAttackedTicks, player.getUuid(), 1);
			Tracker.hungerManager.get(player.getUuid()).update();
			
			Vector velocity = playerVelocity.computeIfAbsent(player.getUuid(), (uuid) -> new Vector());
			
			Position newPosition = player.getPosition();
			Position position = previousPosition.getOrDefault(player.getUuid(), newPosition);
			
			double dx = newPosition.getX() - position.getX();
			double dy = newPosition.getY() - position.getY();
			double dz = newPosition.getZ() - position.getZ();
			
			velocity.setX(dx * MinecraftServer.TICK_PER_SECOND);
			velocity.setY(dy * MinecraftServer.TICK_PER_SECOND);
			velocity.setZ(dz * MinecraftServer.TICK_PER_SECOND);
			
			previousPosition.put(player.getUuid(), newPosition.clone());
		});
		
		node.addListener(EntityTickEvent.class, event -> {
			if (Tracker.invulnerableTime.getOrDefault(event.getEntity().getUuid(), 0) > 0) {
				Tracker.decreaseInt(Tracker.invulnerableTime, event.getEntity().getUuid(), 1);
			}
		});
		
		node.addListener(PlayerUseItemEvent.class, event -> {
			if (Tracker.hasCooldown(event.getPlayer(), event.getItemStack().getMaterial())) {
				event.setCancelled(true);
			}
		});
		
		node.addListener(PlayerPreEatEvent.class, event -> {
			if (Tracker.hasCooldown(event.getPlayer(), event.getFoodItem().getMaterial())) {
				event.setCancelled(true);
			}
		});
		
		node.addListener(EntityVelocityEvent.class, event -> {
			if (event.getEntity() instanceof Player) {
				playerVelocity.put(event.getEntity().getUuid(), event.getVelocity());
			}
		});
		
		node.addListener(PlayerItemAnimationEvent.class, event ->
				itemUseStartTime.put(event.getPlayer().getUuid(), System.currentTimeMillis()));
		
		node.addListener(PlayerMoveEvent.class, event -> {
			Player player = event.getPlayer();
			if (EntityUtils.isClimbing(player)) {
				lastClimbedBlock.put(player.getUuid(), Objects.requireNonNull(player.getInstance())
						.getBlock(player.getPosition().toBlockPosition()));
			}
		});
		
		MinecraftServer.getSchedulerManager().buildTask(Tracker::updateCooldown).repeat(1, TimeUnit.SERVER_TICK).schedule();
	}
}

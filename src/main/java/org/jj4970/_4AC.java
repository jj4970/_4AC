package org.jj4970;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.block.Block;
import org.bukkit.entity.Boat;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class _4AC extends JavaPlugin implements Listener {

    private final Map<UUID, Long> timerLastPacket = new ConcurrentHashMap<>();
    private final Map<UUID, Long> timerBalance = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> airTicks = new ConcurrentHashMap<>();
    private final Map<UUID, Double> lastYVelocity = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> hoverTicks = new ConcurrentHashMap<>();
    private final Map<UUID, Long> velocityImmunity = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastGlideTime = new ConcurrentHashMap<>();
    
    private final Map<UUID, Double> speedVL = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> generalVL = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> elytraUpwardTicks = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> vehicleUpTicks = new ConcurrentHashMap<>();
    private final Map<UUID, Location> safeLocations = new ConcurrentHashMap<>();

    private double timerBalanceBuffer;
    private double speedMaxHDist;
    private double elytraMaxHDist;
    private double elytraMaxVDist;
    private double elytraAccelerationLimit;
    private boolean elytraForbidInfiniteHeight;
    private boolean blockEntityFly;
    private boolean strictBoatFly;
    private boolean autoKick;
    private int kickThreshold;
    
    private double entitySpeedNormal;
    private double entitySpeedIce;
    private double entityStepVDist;
    
    private double flyGravityLenience;
    private int flyMaxHoverTicks;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfigValues();
        getServer().getPluginManager().registerEvents(this, this);
        
        Bukkit.getGlobalRegionScheduler().runAtFixedRate(this, task -> {
            for (Map.Entry<UUID, Integer> entry : generalVL.entrySet()) {
                int v = entry.getValue();
                if (v > 0) {
                    generalVL.put(entry.getKey(), v - 1);
                }
            }
        }, 200L, 200L);
    }
    
    private void loadConfigValues() {
        getConfig().options().copyDefaults(true);
        saveConfig();
        
        timerBalanceBuffer = getConfig().getDouble("limits.timer_balance_buffer", 250.0);
        speedMaxHDist = getConfig().getDouble("limits.speed_max_hdist", 2.5);
        elytraMaxHDist = getConfig().getDouble("limits.elytra_max_hdist", 4.0);
        elytraMaxVDist = getConfig().getDouble("limits.elytra_max_vdist", 6.0);
        elytraAccelerationLimit = getConfig().getDouble("limits.elytra_bouncy_acceleration", 1.5);
        elytraForbidInfiniteHeight = getConfig().getBoolean("limits.elytra_max_height_gain_without_rocket", true);
        
        entitySpeedNormal = getConfig().getDouble("limits.entity_speed_max_hdist_normal", 1.0);
        entitySpeedIce = getConfig().getDouble("limits.entity_speed_max_hdist_ice", 4.0);
        entityStepVDist = getConfig().getDouble("limits.entity_step_max_vdist", 1.1);
        
        flyGravityLenience = getConfig().getDouble("limits.fly_gravity_lenience", 0.04);
        flyMaxHoverTicks = getConfig().getInt("limits.fly_max_hover_ticks", 3);
        kickThreshold = getConfig().getInt("limits.kick_threshold", 40);

        blockEntityFly = getConfig().getBoolean("modules.block_entity_fly", true);
        strictBoatFly = getConfig().getBoolean("modules.strict_boat_fly", true);
        autoKick = getConfig().getBoolean("modules.auto_kick", true);
    }

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        if (p.getGameMode() != GameMode.SURVIVAL) return;

        UUID uuid = p.getUniqueId();
        Location from = e.getFrom();
        Location to = e.getTo();
        if (to == null || from.getWorld() != to.getWorld()) return;

        double dy = to.getY() - from.getY();
        double hDistSq = Math.pow(to.getX() - from.getX(), 2) + Math.pow(to.getZ() - from.getZ(), 2);
        double hDist = Math.sqrt(hDistSq);
        long now = System.currentTimeMillis();

        long lastPacketTime = timerLastPacket.getOrDefault(uuid, now - 50);
        long balance = timerBalance.getOrDefault(uuid, 0L);
        
        long diff = now - lastPacketTime;
        
        balance += (diff - 38);
        
        if (dy < -0.1 && balance < -timerBalanceBuffer) {
            balance = -(long)timerBalanceBuffer;
        }
        
        if (balance > 150) balance = 150;
        
        timerLastPacket.put(uuid, now);
        timerBalance.put(uuid, balance);
        
        if (balance < -(long)timerBalanceBuffer) {
            e.setCancelled(true);
            addAlert(p, "Timer/FastMath");
            return;
        }

        if (blockEntityFly && p.isInsideVehicle()) {
            return;
        }

        if (p.isGliding()) {
            if (hDist > elytraMaxHDist || dy > elytraMaxVDist) {
                handleLagback(e, p, "ElytraSpeed");
                return;
            }
            
            if (elytraForbidInfiniteHeight) {
                if (dy > 0) {
                    int upTicks = elytraUpwardTicks.getOrDefault(uuid, 0) + 1;
                    elytraUpwardTicks.put(uuid, upTicks);
                    if (upTicks > 120) {
                        handleLagback(e, p, "Elytra Infinite Height");
                    }
                } else {
                    elytraUpwardTicks.put(uuid, 0);
                }
            }
        }

        long lastGlide = lastGlideTime.getOrDefault(uuid, 0L);
        if (p.isGliding()) {
            lastGlideTime.put(uuid, now);
            lastGlide = now;
        }
        boolean recentGlide = (now - lastGlide) < 1500;

        boolean onBlock = isSolid(p.getBoundingBox(), p.getWorld(), -0.1) || isOnEntity(p);
        long immuneTo = velocityImmunity.getOrDefault(uuid, 0L);
        boolean immune = now < immuneTo || recentGlide;

        if (onBlock || p.isGliding() || recentGlide) {
            safeLocations.put(uuid, from.clone());
        }
        
        if (onBlock || p.isSwimming() || p.isClimbing() || p.isRiptiding() || p.isFlying() || p.isGliding() || p.getVehicle() != null
                || p.getLocation().getBlock().getType() == org.bukkit.Material.COBWEB || p.getLocation().getBlock().getType() == org.bukkit.Material.POWDER_SNOW) {
            airTicks.put(uuid, 0);
            hoverTicks.put(uuid, 0);
            lastYVelocity.put(uuid, 0.0);
        } else {
            int ticks = airTicks.getOrDefault(uuid, 0) + 1;
            airTicks.put(uuid, ticks);
            
            double lastY = lastYVelocity.getOrDefault(uuid, 0.0);
            double expectedDy = (lastY - 0.08) * 0.98;
            
            double maxJump = 0.42f;
            if (p.hasPotionEffect(org.bukkit.potion.PotionEffectType.JUMP_BOOST)) {
                maxJump += 0.1 * (p.getPotionEffect(org.bukkit.potion.PotionEffectType.JUMP_BOOST).getAmplifier() + 1);
            }
            
            boolean isValidJump = ticks == 1 && dy > 0 && Math.abs(dy - maxJump) < 0.001; // exact jump
            double gravDiff = dy > expectedDy ? (dy - expectedDy) : 0.0;
            
            if (dy > -0.07 && dy < 0.07) {
                int hover = hoverTicks.getOrDefault(uuid, 0) + 1;
                hoverTicks.put(uuid, hover);
                if (hover > flyMaxHoverTicks + 4) {
                    if (!immune) {
                        handleLagback(e, p, "Hover/Glide");
                        lastYVelocity.put(uuid, 0.0);
                        airTicks.put(uuid, 0);
                        return;
                    }
                }
            } else {
                hoverTicks.put(uuid, 0);
            }
            
            if (!p.hasPotionEffect(org.bukkit.potion.PotionEffectType.LEVITATION)) {
                if (!immune) {
                    if (!isValidJump) {
                        if (ticks == 1 && dy > 0) {
                            boolean onBounceBlock = false;
                            Material bType = from.clone().add(0, -0.5, 0).getBlock().getType();
                            if (bType.name().contains("SLIME") || bType.name().contains("BED")) {
                                onBounceBlock = true;
                            }
                            
                            if (!onBounceBlock && dy > maxJump + 0.65) {
                                handleLagback(e, p, "Invalid Ascent");
                                lastYVelocity.put(uuid, 0.0);
                                airTicks.put(uuid, 0);
                                return;
                            }
                        } else if (dy > 0 && gravDiff > flyGravityLenience) {
                            handleLagback(e, p, "Gravity/Flight");
                            lastYVelocity.put(uuid, 0.0);
                            airTicks.put(uuid, 0);
                            return;
                        }
                    }
                }
            }
            
            double applyLastY = dy;
            if (applyLastY < -3.92) applyLastY = -3.92;
            
            lastYVelocity.put(uuid, applyLastY);
        }

        if (!p.isFlying() && !p.isGliding() && !p.isInsideVehicle()) {
            boolean nearIce = false;
            boolean nearWater = p.isSwimming() || p.getLocation().getBlock().getType().name().contains("WATER") || 
                                from.getBlock().getType().name().contains("WATER");
            
            int cMinX = (int) Math.floor(p.getBoundingBox().getMinX() - 0.2);
            int cMaxX = (int) Math.floor(p.getBoundingBox().getMaxX() + 0.2);
            int cMinZ = (int) Math.floor(p.getBoundingBox().getMinZ() - 0.2);
            int cMaxZ = (int) Math.floor(p.getBoundingBox().getMaxZ() + 0.2);
            int cY = (int) Math.floor(p.getLocation().getY() - 0.5);
            
            for (int x = cMinX; x <= cMaxX; x++) {
                for (int z = cMinZ; z <= cMaxZ; z++) {
                    String mName = from.getWorld().getBlockAt(x, cY, z).getType().name();
                    if (mName.contains("ICE")) nearIce = true;
                }
            }
            
            double maxSpeed;
            int aT = onBlock ? 0 : airTicks.getOrDefault(uuid, 0);
            if (aT == 0) {
                maxSpeed = 0.45;
            } else if (aT == 1) {
                maxSpeed = 0.65;
            } else if (aT < 4) {
                maxSpeed = 0.50;
            } else {
                maxSpeed = 0.40;
            }
            
            if (nearIce) maxSpeed += 0.4;
            if (nearWater) maxSpeed += 0.2;
            
            if (p.hasPotionEffect(org.bukkit.potion.PotionEffectType.SPEED)) {
                maxSpeed += 0.15 * (p.getPotionEffect(org.bukkit.potion.PotionEffectType.SPEED).getAmplifier() + 1);
            }
            
            double enforcedLimit;
            if (immune) {
                enforcedLimit = 0.0;
                if (recentGlide) {
                    long tSinceGlide = now - lastGlide;
                    if (tSinceGlide < 700) enforcedLimit = Math.max(enforcedLimit, elytraMaxHDist);
                    else enforcedLimit = Math.max(enforcedLimit, elytraAccelerationLimit);
                }
                if (now < immuneTo) enforcedLimit = Math.max(enforcedLimit, speedMaxHDist);
            } else {
                enforcedLimit = Math.min(speedMaxHDist, maxSpeed + 0.08);
            }
            
            double currentVL = speedVL.getOrDefault(uuid, 0.0);
            
            if (hDist > enforcedLimit) {
                double distDiff = hDist - enforcedLimit;
                currentVL += (distDiff * 10.0) + 1.0;
                if (currentVL > 4.5) {
                    handleLagback(e, p, "Speed");
                    speedVL.put(uuid, 3.5);
                    return;
                }
            } else {
                currentVL -= 0.15;
                if (currentVL < 0.0) currentVL = 0.0;
            }
            speedVL.put(uuid, currentVL);
        }
    }

    private void handleLagback(PlayerMoveEvent e, Player p, String type) {
        UUID u = p.getUniqueId();
        addAlert(p, type);
        
        int vl = generalVL.getOrDefault(u, 0);
        if (vl >= 15) {
            e.setCancelled(true);
            return;
        }

        if (vl >= 5) {
            Location safe = safeLocations.getOrDefault(u, e.getFrom());
            e.setTo(safe);
            p.getScheduler().run(this, task -> p.setVelocity(new Vector(0, 0, 0)), null);
        } else {
            e.setTo(e.getFrom());
        }
        
        if (type.contains("Elytra")) {
            p.getScheduler().run(this, task -> p.setVelocity(new Vector(0, -0.5, 0)), null);
        }
    }

    private void addAlert(Player p, String type) {
        UUID u = p.getUniqueId();
        int vl = generalVL.getOrDefault(u, 0) + 1;
        generalVL.put(u, vl);

        if (vl % 3 == 0) {
            String msg = "§c[_4AC] §f" + p.getName() + " §7flagged §f" + type + " §7(VL: " + vl + ")";
            Bukkit.getOnlinePlayers().stream().filter(org.bukkit.entity.Entity::isOp).forEach(a -> a.sendMessage(msg));
            getLogger().info(p.getName() + " flagged " + type + " (VL: " + vl + ")");
        }
        
        if (autoKick && vl >= kickThreshold) {
            p.getScheduler().run(this, task -> {
                String kickMsg = "§cDisconnected\n\n§fReason: §7" + type + "\n\n§8[_4AC]";
                p.kickPlayer(kickMsg);
            }, null);
            generalVL.put(u, 0);
        }
    }

    private boolean isSolid(BoundingBox box, org.bukkit.World world, double yOff) {
        double minX = box.getMinX() + 0.005;
        double maxX = box.getMaxX() - 0.005;
        double minZ = box.getMinZ() + 0.005;
        double maxZ = box.getMaxZ() - 0.005;
        
        double minY = box.getMinY() + yOff;
        double maxY = box.getMinY() + Math.max(0.0, yOff) + 0.1;
        
        BoundingBox footprint = new BoundingBox(minX, minY, minZ, maxX, maxY, maxZ);
        
        int bMinX = (int) Math.floor(footprint.getMinX());
        int bMinY = (int) Math.floor(footprint.getMinY());
        int bMinZ = (int) Math.floor(footprint.getMinZ());
        int bMaxX = (int) Math.floor(footprint.getMaxX());
        int bMaxY = (int) Math.floor(footprint.getMaxY());
        int bMaxZ = (int) Math.floor(footprint.getMaxZ());
        
        for (int x = bMinX; x <= bMaxX; x++) {
            for (int y = bMinY; y <= bMaxY; y++) {
                for (int z = bMinZ; z <= bMaxZ; z++) {
                    Block b = world.getBlockAt(x, y, z);
                    Material m = b.getType();
                    if (m == Material.WATER || m == Material.LAVA) return true;
                    
                    boolean checkCollision = m.isSolid() 
                            || m.name().contains("REPEATER") 
                            || m.name().contains("COMPARATOR") 
                            || m.name().contains("DIODE") 
                            || m.name().contains("DAYLIGHT") 
                            || m.name().contains("LILY_PAD") 
                            || m.name().contains("CARPET");
                            
                    if (checkCollision) {
                        BoundingBox blockBox = b.getBoundingBox();
                        if (blockBox.overlaps(footprint)) return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean isOnEntity(Player p) {
        BoundingBox box = p.getBoundingBox().clone().shift(0, -0.1, 0).expand(0.1, 0.0, 0.1);
        for (Entity e : p.getWorld().getNearbyEntities(box)) {
            if (e.getEntityId() != p.getEntityId()) {
                if (!(e instanceof org.bukkit.entity.Item) && !(e instanceof org.bukkit.entity.ExperienceOrb) && !(e instanceof org.bukkit.entity.Projectile)) {
                    return true;
                }
            }
        }
        return false;
    }

    @EventHandler
    public void onVelocity(PlayerVelocityEvent e) {
        double power = e.getVelocity().length();
        if (power > 0.1) {
            long ticksConfigured = (long) (power * 40);
            velocityImmunity.put(e.getPlayer().getUniqueId(), System.currentTimeMillis() + (ticksConfigured * 50));
        }
    }

    @EventHandler
    public void onVehicleMove(VehicleMoveEvent e) {
        if (!blockEntityFly) return;
        Entity v = e.getVehicle();
        if (v.getPassengers().isEmpty()) return;
        
        Player p = null;
        for (Entity ent : v.getPassengers()) {
            if (ent instanceof Player) {
                p = (Player) ent;
                break;
            }
        }
        if (p == null || p.getGameMode() != GameMode.SURVIVAL) return;

        Location from = e.getFrom();
        Location to = e.getTo();
        
        double dy = to.getY() - from.getY();
        double hDistSq = Math.pow(to.getX() - from.getX(), 2) + Math.pow(to.getZ() - from.getZ(), 2);
        double hDist = Math.sqrt(hDistSq);
        
        boolean onGround = isSolid(v.getBoundingBox(), v.getWorld(), -0.5);
        
        boolean onIce = false;
        Location loc = v.getLocation();
        for (double x = -0.8; x <= 0.8; x += 0.8) {
            for (double z = -0.8; z <= 0.8; z += 0.8) {
                Material m = loc.clone().add(x, -0.5, z).getBlock().getType();
                if (m.name().contains("ICE")) {
                    onIce = true;
                    break;
                }
            }
            if (onIce) break;
        }

        double maxHDist = onIce ? entitySpeedIce : entitySpeedNormal;
        UUID vId = v.getUniqueId();

        if (!onGround) {
            int vAir = airTicks.getOrDefault(vId, 0) + 1;
            airTicks.put(vId, vAir);
            
            if (dy > entityStepVDist) {
                handleVehicleHack(v, from, p, "Entity Step/Fly");
                return;
            }

            if (v instanceof Boat) {
                if (strictBoatFly) {
                    if ((vAir > 4 && dy > -0.3) || (vAir > 15 && dy > -0.8)) {
                        Material boxMat = v.getLocation().getBlock().getType();
                        if (!boxMat.name().contains("WATER") && !boxMat.name().contains("BUBBLE")) {
                            handleVehicleHack(v, from, p, "BoatFly");
                            return;
                        }
                    }
                }
            } else {
                if (dy > 0) {
                    int upT = vehicleUpTicks.getOrDefault(vId, 0) + 1;
                    vehicleUpTicks.put(vId, upT);
                    if (upT > 15) {
                        handleVehicleHack(v, from, p, "EntityFly (Upward)");
                        return;
                    }
                } else {
                    vehicleUpTicks.put(vId, 0);
                }
                
                if (dy > -0.05 && dy < 0.05) {
                    int hovT = hoverTicks.getOrDefault(vId, 0) + 1;
                    hoverTicks.put(vId, hovT);
                    if (hovT > 6) {
                        handleVehicleHack(v, from, p, "EntityFly (Hover)");
                        return;
                    }
                } else {
                    hoverTicks.put(vId, 0);
                }
                
                if (vAir > 40 && dy > -0.3) {
                    handleVehicleHack(v, from, p, "EntityFly (Glide)");
                    return;
                }
            }
            
            if (hDist > maxHDist * 0.85) {
                handleVehicleHack(v, from, p, "EntitySpeed (Air)");
                return;
            }
        } else {
            airTicks.put(vId, 0);
            vehicleUpTicks.put(vId, 0);
            hoverTicks.put(vId, 0);

            if (dy > entityStepVDist) {
                handleVehicleHack(v, from, p, "EntityStep");
                return;
            }
            if (hDist > maxHDist) {
                handleVehicleHack(v, from, p, "EntitySpeed");
                return;
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID u = e.getPlayer().getUniqueId();
        timerLastPacket.remove(u);
        timerBalance.remove(u);
        airTicks.remove(u);
        lastYVelocity.remove(u);
        hoverTicks.remove(u);
        generalVL.remove(u);
        elytraUpwardTicks.remove(u);
        velocityImmunity.remove(u);
        lastGlideTime.remove(u);
        safeLocations.remove(u);
        speedVL.remove(u);
    }

    private void handleVehicleHack(Entity v, Location from, Player p, String type) {
        v.setVelocity(new Vector(0, -0.2, 0));
        v.teleport(from);
        v.eject();
        addAlert(p, type);
    }
}

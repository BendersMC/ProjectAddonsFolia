package me.simplicitee.project.addons.util;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import me.simplicitee.project.addons.ProjectAddons;
import org.bukkit.Bukkit;
import org.bukkit.Particle;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;

public class AnimatedBlock {

	private static final Map<Block, AnimatedBlock> INSTANCES = new HashMap<>();

	private final Block block;
	private BlockData original;
	private final Particle effect;
	private final LinkedList<AnimationStep> animation;
	private final boolean cycle;
	private final boolean revert;
	private final Queue<Runnable> destroyTasks;
	private volatile boolean destroyed = false;
	private long prevTime;
	private volatile ScheduledTask animationTask;

	AnimatedBlock(Block block, Queue<AnimationStep> animation, boolean cycle, boolean revert, Queue<Runnable> revertTasks, Particle effect) {
		this.block = block;
		this.effect = effect;
		this.animation = new LinkedList<>(animation);
		this.destroyTasks = new LinkedList<>(revertTasks);
		this.cycle = cycle;
		this.revert = revert;

		INSTANCES.put(block, this);

		Bukkit.getRegionScheduler().execute(ProjectAddons.instance, block.getLocation(), this::initializeOnRegionThread);
	}

	private void initializeOnRegionThread() {
		if (destroyed) {
			INSTANCES.remove(block);
			return;
		}

		AnimationStep firstStep = animation.peek();
		if (firstStep == null) {
			destroyOnRegionThread();
			return;
		}

		original = block.getBlockData();
		block.setBlockData(firstStep.getBlockData(), false);
		prevTime = System.currentTimeMillis();

		animationTask = Bukkit.getRegionScheduler().runAtFixedRate(
				ProjectAddons.instance,
				block.getLocation(),
				this::tickAnimation,
				1L,
				1L);
	}

	private void tickAnimation(ScheduledTask task) {
		if (destroyed) {
			task.cancel();
			return;
		}

		if (!advanceAnimationStep()) {
			task.cancel();
		}
	}

	private boolean advanceAnimationStep() {
		if (destroyed) {
			return false;
		}

		AnimationStep step = animation.peek();
		if (step == null) {
			destroyOnRegionThread();
			return false;
		}

		if (effect != null && Math.random() > 0.5) {
			block.getWorld().spawnParticle(effect, block.getLocation().add(0.5, 1, 0.5), 1, 0.4, 0, 0.4);
		}

		if (prevTime + step.getDuration() <= System.currentTimeMillis()) {
			prevTime = System.currentTimeMillis();
			animation.poll();

			if (cycle) {
				animation.add(step);
			} else if (animation.isEmpty()) {
				destroyOnRegionThread();
				return false;
			}

			AnimationStep nextStep = animation.peek();
			if (nextStep == null) {
				destroyOnRegionThread();
				return false;
			}
			block.setBlockData(nextStep.getBlockData(), false);
		}

		return true;
	}

	public Block getBlock() {
		if (destroyed) {
			return null;
		}

		return block;
	}

	public AnimationStep getCurrentStep() {
		if (destroyed) {
			return null;
		}

		return animation.peek();
	}

	public boolean isCycling() {
		if (destroyed) {
			return false;
		}

		return cycle;
	}

	public void destroy() {
		Bukkit.getRegionScheduler().execute(ProjectAddons.instance, block.getLocation(), this::destroyOnRegionThread);
	}

	private void destroyOnRegionThread() {
		if (destroyed) {
			return;
		}

		cancelAnimationTask();

		if (revert && original != null) {
			block.setBlockData(original, true);
		}

		animation.clear();
		destroyed = true;
		INSTANCES.remove(block);

		while (!destroyTasks.isEmpty()) {
			destroyTasks.poll().run();
		}
	}

	private void cancelAnimationTask() {
		ScheduledTask task = animationTask;
		if (task != null && !task.isCancelled()) {
			task.cancel();
		}
		animationTask = null;
	}

	public static void destroyAll() {
		List<AnimatedBlock> activeAnimations = new ArrayList<>(INSTANCES.values());
		INSTANCES.clear();

		for (AnimatedBlock animation : activeAnimations) {
			Bukkit.getRegionScheduler().execute(
					ProjectAddons.instance,
					animation.block.getLocation(),
					animation::destroyOnRegionThread);
		}
	}

	public static class AnimationStep {
		private final BlockData data;
		private final long duration;

		public AnimationStep(BlockData data, long duration) {
			this.data = data;
			this.duration = duration;
		}

		public BlockData getBlockData() {
			return data;
		}

		public long getDuration() {
			return duration;
		}
	}
}

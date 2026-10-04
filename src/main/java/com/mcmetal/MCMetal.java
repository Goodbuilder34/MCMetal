package com.mcmetal;

import com.mcmetal.metal.MetalBackend;
import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MCMetal implements ClientModInitializer {
	public static final Logger LOGGER = LoggerFactory.getLogger("mcmetal");

	/** {@code -Dmcmetal.disable=true} falls back to the vanilla backends (useful for A/B benchmarking). */
	public static boolean isEnabled() {
		return !Boolean.getBoolean("mcmetal.disable") && MetalBackend.isSupportedPlatform();
	}

	@Override
	public void onInitializeClient() {
	}
}

/*
 * Copyright (c) 2025, LordStrange
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.catmanmode;

import com.google.inject.Provides;
import java.io.BufferedInputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.Player;
import net.runelite.api.PlayerComposition;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

@PluginDescriptor(
	name = "Catman Mode",
	description = "Transforms other players into cats (client-side only)",
	tags = {"cat", "pet", "fun", "transform", "players", "catman"}
)
@Slf4j
public class CatmanModePlugin extends Plugin
{
	private static final String PET_OPTION = "Pet";

	// Cat animation IDs
	private static final int CAT_IDLE = 317;      // Standing idle
	private static final int CAT_WALK = 314;      // Walking
	private static final int CAT_PAW = 2663;      // Pawing (for actions)
	private static final int CAT_SLEEP = 2159;    // Sleeping
	private static final int CAT_ROLL = 318;      // Rolling over
	private static final int CAT_POUNCE = 319;    // Pouncing
	private static final int CAT_ARCH = 1823;     // Arching back
	private static final int CAT_SIT = 2694;      // Sitting

	// Additional cat animations for isCatAnimation check
	private static final int CAT_ATTACK = 315;
	private static final int CAT_BLOCK = 316;
	private static final int CAT_DEATH = 320;
	private static final int CAT_LAY_DOWN = 2160;
	private static final int CAT_GET_UP = 2161;

	// POH Menagerie cats
	private static final int[] CAT_NPC_IDS = {
		6662,  // Grey cat
		6663,  // White cat
		6664,  // Brown cat
		6665,  // Black cat
		6666,  // Brown/grey cat
		6667   // Blue/grey cat
	};

	// Cat NPC IDs including hellcat
	private static final int[] CAT_NPC_IDS_WITH_HELLCAT = {
		6662,  // Grey cat
		6663,  // White cat
		6664,  // Brown cat
		6665,  // Black cat
		6666,  // Brown/grey cat
		6667,  // Blue/grey cat
		6668   // Hellcat
	};

	// Kitten NPC IDs (for players under level 80)
	private static final int[] KITTEN_NPC_IDS = {
		5591,  // Grey kitten
		5592,  // White kitten
		5593,  // Brown kitten
		5594,  // Black kitten
		5595,  // Brown/grey kitten
		5596   // Blue/grey kitten
	};

	// Kitten NPC IDs including hellkitten
	private static final int[] KITTEN_NPC_IDS_WITH_HELLCAT = {
		5591,  // Grey kitten
		5592,  // White kitten
		5593,  // Brown kitten
		5594,  // Black kitten
		5595,  // Brown/grey kitten
		5596,  // Blue/grey kitten
		5597   // Hellkitten
	};

	// Combat level threshold for kitten vs cat
	private static final int KITTEN_LEVEL_THRESHOLD = 80;

	// Duration for overhead text to display (in milliseconds)
	private static final int OVERHEAD_TEXT_DURATION = 3000;

	// Default player animations (unarmed)
	private static final int DEFAULT_IDLE = 808;
	private static final int DEFAULT_WALK = 819;
	private static final int DEFAULT_RUN = 824;
	private static final int DEFAULT_ROTATE = 823;

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ScheduledExecutorService executor;

	@Inject
	private CatmanModeConfig config;

	// Track which cat type each player has been assigned
	private final Map<String, Integer> playerCatTypes = new HashMap<>();

	// Track if we've set up animations for a player
	private final Map<String, Boolean> animationsSet = new HashMap<>();

	// Track players we've already added Pet option for in current menu
	private final Set<String> petOptionAddedFor = new HashSet<>();

	// Cached purring sound clip
	private Clip purringClip;

	@Provides
	CatmanModeConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(CatmanModeConfig.class);
	}

	@Override
	protected void startUp() throws Exception
	{
		log.info("Catman Mode plugin started - everyone is now a cat!");
		loadPurringSound();
	}

	@Override
	protected void shutDown() throws Exception
	{
		log.info("Catman Mode plugin stopped - restoring human forms");
		clientThread.invokeLater(this::restoreAllPlayers);
		playerCatTypes.clear();
		animationsSet.clear();
		petOptionAddedFor.clear();

		if (purringClip != null)
		{
			purringClip.close();
			purringClip = null;
		}
	}

	private void loadPurringSound()
	{
		try
		{
			InputStream audioStream = getClass().getResourceAsStream("Purring.wav");
			if (audioStream == null)
			{
				log.warn("Could not find Purring.wav sound file in plugin resources");
				return;
			}

			InputStream bufferedIn = new BufferedInputStream(audioStream);
			AudioInputStream ais = AudioSystem.getAudioInputStream(bufferedIn);
			purringClip = AudioSystem.getClip();
			purringClip.open(ais);
			log.info("Purring sound loaded successfully");
		}
		catch (Exception e)
		{
			log.warn("Could not load purring sound: {}", e.getMessage(), e);
		}
	}

	private void playPurringSound()
	{
		if (purringClip == null)
		{
			return;
		}

		executor.submit(() ->
		{
			try
			{
				if (purringClip.isRunning())
				{
					purringClip.stop();
				}
				purringClip.setFramePosition(0);
				purringClip.start();
			}
			catch (Exception e)
			{
				log.warn("Could not play purring sound: {}", e.getMessage());
			}
		});
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGIN_SCREEN)
		{
			playerCatTypes.clear();
			animationsSet.clear();
		}
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!event.getGroup().equals("catmanmode"))
		{
			return;
		}

		// Clear all state when any config changes
		playerCatTypes.clear();
		animationsSet.clear();
	}

	@Subscribe
	public void onMenuEntryAdded(MenuEntryAdded event)
	{
		// Only add Pet option when right-clicking a player (PLAYER_FIRST_OPTION through PLAYER_EIGHTH_OPTION)
		int type = event.getType();
		if (type < MenuAction.PLAYER_FIRST_OPTION.getId() || type > MenuAction.PLAYER_EIGHTH_OPTION.getId())
		{
			// Clear the tracking set when we're not on a player menu
			petOptionAddedFor.clear();
			return;
		}

		// Get the player from the menu entry
		MenuEntry menuEntry = event.getMenuEntry();
		if (menuEntry == null)
		{
			return;
		}

		Player targetPlayer = menuEntry.getPlayer();
		if (targetPlayer == null || targetPlayer == client.getLocalPlayer())
		{
			return;
		}

		String playerName = targetPlayer.getName();
		if (playerName == null)
		{
			return;
		}

		// Only add Pet option once per player per menu
		if (petOptionAddedFor.contains(playerName))
		{
			return;
		}
		petOptionAddedFor.add(playerName);

		// Add Pet option for this specific player
		client.getMenu().createMenuEntry(-1)
			.setOption(PET_OPTION)
			.setTarget("<col=ffffff>" + playerName + "</col>")
			.setType(MenuAction.RUNELITE)
			.setIdentifier(0)
			.onClick(e -> petPlayer(targetPlayer));
	}

	@Subscribe
	public void onClientTick(ClientTick event)
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}

		Player localPlayer = client.getLocalPlayer();
		if (localPlayer == null)
		{
			return;
		}

		// Transform all other players into cats (not self)
		for (Player player : client.getPlayers())
		{
			if (player == null || player == localPlayer)
			{
				continue;
			}

			transformPlayerToCat(player);
		}
	}

	private void petPlayer(Player target)
	{
		if (target == null)
		{
			return;
		}

		Player localPlayer = client.getLocalPlayer();
		if (localPlayer == null)
		{
			return;
		}

		// Play purring sound
		playPurringSound();

		// Play roll animation on the cat (being petted)
		target.setAnimation(CAT_ROLL);
		target.setAnimationFrame(0);

		// Set overhead text
		target.setOverheadText("Prrr~");
		localPlayer.setOverheadText("What a good cat!");

		// Schedule clearing the overhead text after delay
		executor.schedule(() -> clientThread.invokeLater(() ->
		{
			// Clear overhead text if it's still our message
			if (target.getOverheadText() != null && target.getOverheadText().equals("Prrr~"))
			{
				target.setOverheadText("");
			}
			Player currentLocal = client.getLocalPlayer();
			if (currentLocal != null && currentLocal.getOverheadText() != null
				&& currentLocal.getOverheadText().equals("What a good cat!"))
			{
				currentLocal.setOverheadText("");
			}
		}), OVERHEAD_TEXT_DURATION, TimeUnit.MILLISECONDS);
	}

	private void transformPlayerToCat(Player player)
	{
		PlayerComposition composition = player.getPlayerComposition();
		if (composition == null)
		{
			return;
		}

		// Get player identifier
		String playerName = player.getName();
		if (playerName == null)
		{
			playerName = String.valueOf(player.hashCode());
		}

		// Check if player should be a kitten (combat level < 80)
		int combatLevel = player.getCombatLevel();
		boolean isKitten = combatLevel < KITTEN_LEVEL_THRESHOLD;

		// Choose cat/kitten array based on config and combat level
		int[] npcArray;
		if (isKitten)
		{
			npcArray = config.includeHellcats() ? KITTEN_NPC_IDS_WITH_HELLCAT : KITTEN_NPC_IDS;
		}
		else
		{
			npcArray = config.includeHellcats() ? CAT_NPC_IDS_WITH_HELLCAT : CAT_NPC_IDS;
		}

		// Get or assign a cat/kitten type for this player
		int catNpcId;
		if (config.randomColors())
		{
			// Use a key that includes kitten status to handle level changes
			String typeKey = playerName + (isKitten ? "_kitten" : "_cat");
			if (!playerCatTypes.containsKey(typeKey))
			{
				playerCatTypes.put(typeKey, npcArray[Math.abs(playerName.hashCode()) % npcArray.length]);
			}
			catNpcId = playerCatTypes.get(typeKey);
		}
		else
		{
			catNpcId = npcArray[0];
		}

		// Always ensure the player is transformed (re-apply if equipment changed)
		if (composition.getTransformedNpcId() != catNpcId)
		{
			composition.setTransformedNpcId(catNpcId);
			// Reset animation state when re-transforming
			animationsSet.remove(playerName);
		}

		// Set up base animations only once per player
		if (!animationsSet.containsKey(playerName))
		{
			player.setIdlePoseAnimation(CAT_IDLE);
			player.setPoseAnimation(CAT_IDLE);
			player.setWalkAnimation(CAT_WALK);
			player.setRunAnimation(CAT_WALK);
			player.setIdleRotateLeft(CAT_IDLE);
			player.setIdleRotateRight(CAT_IDLE);
			player.setWalkRotateLeft(CAT_WALK);
			player.setWalkRotateRight(CAT_WALK);
			player.setWalkRotate180(CAT_WALK);
			animationsSet.put(playerName, true);
		}

		// Map player action animations to cat animations
		int currentAnim = player.getAnimation();
		if (currentAnim != -1 && !isCatAnimation(currentAnim))
		{
			int catAnim = mapToCatAnimation(currentAnim);
			player.setAnimation(catAnim);
		}
	}

	private boolean isCatAnimation(int animId)
	{
		return animId == CAT_IDLE || animId == CAT_WALK || animId == CAT_PAW ||
			animId == CAT_SLEEP || animId == CAT_ROLL || animId == CAT_POUNCE ||
			animId == CAT_ARCH || animId == CAT_SIT || animId == -1 ||
			animId == CAT_ATTACK || animId == CAT_BLOCK ||
			animId == CAT_DEATH || animId == CAT_LAY_DOWN ||
			animId == CAT_GET_UP;
	}

	private int mapToCatAnimation(int playerAnim)
	{
		// Combat animations (most weapon attacks are in this range)
		if ((playerAnim >= 376 && playerAnim <= 450) ||  // melee attacks
			(playerAnim >= 1156 && playerAnim <= 1170) || // more attacks
			(playerAnim >= 7041 && playerAnim <= 7060) || // whip, etc
			(playerAnim >= 8056 && playerAnim <= 8100))   // special attacks
		{
			return CAT_POUNCE;
		}

		// Magic casting animations
		if ((playerAnim >= 710 && playerAnim <= 729) ||  // standard spellbook
			(playerAnim >= 1161 && playerAnim <= 1170) || // ancient magicks
			playerAnim == 811 || playerAnim == 1162)      // high alch, etc
		{
			return CAT_PAW;
		}

		// Prayer animations
		if (playerAnim >= 645 && playerAnim <= 650)
		{
			return CAT_SIT;
		}

		// Emote animations (dancing, waving, etc)
		if ((playerAnim >= 855 && playerAnim <= 870) ||
			(playerAnim >= 2105 && playerAnim <= 2115) ||
			(playerAnim >= 4274 && playerAnim <= 4285))
		{
			return CAT_ROLL;
		}

		// Skilling animations
		// Mining
		if ((playerAnim >= 624 && playerAnim <= 629) ||
			playerAnim == 7139 || playerAnim == 642)
		{
			return CAT_PAW;
		}

		// Woodcutting
		if ((playerAnim >= 867 && playerAnim <= 879) ||
			playerAnim == 2846 || playerAnim == 8303)
		{
			return CAT_PAW;
		}

		// Fishing
		if ((playerAnim >= 618 && playerAnim <= 623) ||
			playerAnim == 5108 || playerAnim == 6704)
		{
			return CAT_SIT; // Cat sitting while fishing
		}

		// Cooking
		if (playerAnim == 883 || playerAnim == 896)
		{
			return CAT_PAW;
		}

		// Smithing
		if (playerAnim == 898 || playerAnim == 899)
		{
			return CAT_PAW;
		}

		// Crafting/fletching
		if ((playerAnim >= 884 && playerAnim <= 897) ||
			playerAnim == 1248 || playerAnim == 1249)
		{
			return CAT_PAW;
		}

		// Death animation
		if (playerAnim == 836 || playerAnim == 2304)
		{
			return CAT_SLEEP;
		}

		// Blocking/taking damage
		if (playerAnim == 424 || playerAnim == 426 ||
			playerAnim == 1156 || playerAnim == 388)
		{
			return CAT_ARCH; // Scared cat!
		}

		// Eating/drinking
		if (playerAnim == 829 || playerAnim == 1707)
		{
			return CAT_PAW;
		}

		// Default: use cat paw for any unknown action
		return CAT_PAW;
	}

	private void restoreAllPlayers()
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}

		for (Player player : client.getPlayers())
		{
			if (player == null)
			{
				continue;
			}

			PlayerComposition composition = player.getPlayerComposition();
			if (composition != null)
			{
				// Restore human form
				composition.setTransformedNpcId(-1);
			}

			// Reset animations to default player animations
			// These will be overridden by the game when player equipment changes
			player.setIdlePoseAnimation(DEFAULT_IDLE);
			player.setPoseAnimation(DEFAULT_IDLE);
			player.setWalkAnimation(DEFAULT_WALK);
			player.setRunAnimation(DEFAULT_RUN);
			player.setIdleRotateLeft(DEFAULT_IDLE);
			player.setIdleRotateRight(DEFAULT_IDLE);
			player.setWalkRotateLeft(DEFAULT_WALK);
			player.setWalkRotateRight(DEFAULT_WALK);
			player.setWalkRotate180(DEFAULT_ROTATE);
		}
	}
}

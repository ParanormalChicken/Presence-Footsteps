package eu.ha3.mc.presencefootsteps.game.system;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Scanner;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.resources.IReloadableResourceManager;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.client.resources.IResourceManagerReloadListener;
import net.minecraft.client.resources.ResourcePackRepository;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.EnumChatFormatting;

import org.apache.commons.lang3.ArrayUtils;
import org.lwjgl.input.Keyboard;

import eu.ha3.easy.EdgeModel;
import eu.ha3.easy.EdgeTrigger;
import eu.ha3.mc.convenience.Ha3HoldActions;
import eu.ha3.mc.convenience.Ha3KeyHolding;
import eu.ha3.mc.convenience.Ha3KeyManager;
import eu.ha3.mc.convenience.Ha3StaticUtilities;
import eu.ha3.mc.haddon.Identity;
import eu.ha3.mc.haddon.OperatorCaster;
import eu.ha3.mc.haddon.implem.HaddonIdentity;
import eu.ha3.mc.haddon.implem.HaddonImpl;
import eu.ha3.mc.haddon.supporting.SupportsFrameEvents;
import eu.ha3.mc.haddon.supporting.SupportsKeyEvents;
import eu.ha3.mc.haddon.supporting.SupportsTickEvents;
import eu.ha3.mc.presencefootsteps.game.user.PFGuiMenu;
import eu.ha3.mc.presencefootsteps.log.PFLog;
import eu.ha3.mc.presencefootsteps.mcpackage.implem.AcousticsManager;
import eu.ha3.mc.presencefootsteps.mcpackage.implem.BasicPrimitiveMap;
import eu.ha3.mc.presencefootsteps.mcpackage.implem.LegacyCapableBlockMap;
import eu.ha3.mc.presencefootsteps.mcpackage.implem.NormalVariator;
import eu.ha3.mc.presencefootsteps.mcpackage.interfaces.BlockMap;
import eu.ha3.mc.presencefootsteps.mcpackage.interfaces.PrimitiveMap;
import eu.ha3.mc.presencefootsteps.mcpackage.interfaces.Variator;
import eu.ha3.mc.presencefootsteps.parsers.AcousticsJsonReader;
import eu.ha3.mc.presencefootsteps.parsers.BlockMapReader;
import eu.ha3.mc.presencefootsteps.parsers.PrimitiveMapReader;
import eu.ha3.mc.quick.chat.Chatter;
import eu.ha3.mc.quick.keys.KeyWatcher;
import eu.ha3.mc.quick.update.NotifiableHaddon;
import eu.ha3.mc.quick.update.UpdateNotifier;
import eu.ha3.mc.quick.update.Updater;
import eu.ha3.util.property.simple.ConfigProperty;
import eu.ha3.util.property.simple.InputStreamConfigProperty;

public class PFHaddon extends HaddonImpl implements SupportsFrameEvents, SupportsTickEvents, IResourceManagerReloadListener, NotifiableHaddon, Ha3HoldActions, SupportsKeyEvents {
	// Identity
	protected final String NAME = "Presence Footsteps";
	protected final int VERSION = 7;
	protected final String MCVERSION = "1.8";
	protected final String ADDRESS = "http://presencefootsteps.ha3.eu";
	protected final Identity identity = (new HaddonIdentity(NAME, VERSION, MCVERSION, ADDRESS)).setPrefix("u");
	
	// NotifiableHaddon and UpdateNotifier
	private ConfigProperty config; // Can't be final
	private final Chatter chatter = new Chatter(this, "<PF> ");
	private Updater updateNotifier;
	
	// Meta
	private File presenceDir;
	private EdgeTrigger debugButton;
	private boolean debugState; 
	private long pressedOptionsTime;
	private boolean enabled = true;
	
	// System
	private PFResourcePackDealer dealer = new PFResourcePackDealer();
	private PFIsolator isolator;
		
	// Binds
	private KeyBinding keyBindingMain;
	private final int keyBindDefaultCode = Keyboard.KEY_F9;
	private final KeyWatcher watcher = new KeyWatcher(this);
	private final Ha3KeyManager keyManager = new Ha3KeyManager();
	
	// Use once
	private boolean firstTickPassed;
	private int tickRound;
	private boolean hasResourcePacks;
	private boolean hasDisabledResourcePacks;
	private boolean hasResourcePacks_FixMe;
	
	// Pony stuff
	private boolean mlpInstalled;
	private boolean mlpDetectedFirst;
	
	@Override
	public void onLoad() {
		//http://q.mc.ha3.eu/query/pf-litemod-version.json
		updateNotifier = new UpdateNotifier(this, "https://raw.githubusercontent.com/Sollace/Presence-Footsteps/master/version/versions.json?ver=%d");
		
		util().registerPrivateSetter("Entity_nextStepDistance", Entity.class, -1, "nextStepDistance", "field_70150_b", "h");
		util().registerPrivateGetter("isJumping", EntityLivingBase.class, -1, "isJumping", "field_70703_bu", "aW");
		
		presenceDir = new File(util().getMcFolder(), "presencefootsteps");
		
		if (!presenceDir.exists()) presenceDir.mkdirs();
		
		debugButton = new EdgeTrigger(new EdgeModel() {
			@Override
			public void onTrueEdge() {
				pressedOptionsTime = System.currentTimeMillis();
				debugState = true;
			}
			
			@Override
			public void onFalseEdge() {
				if (debugState) {
					if (PFLog.getDebugEnabled()) {
						PFLog.setDebugEnabled(false);
						chatter.printChat(I18n.format("pf.debug.off"));
					} else {
						PFLog.setDebugEnabled(true);
						chatter.printChat(I18n.format("pf.debug.on"));
					}
					reloadEverything(false);
				}
			}
		});
		
		reloadEverything(false);// Config is loaded here
		
		if (mlpInstalled = isInstalledMLP()) {
			if (getConfig().getBoolean("mlp.detected") == false) {
				getConfig().setProperty("mlp.detected", true);
				saveConfig();
				mlpDetectedFirst = true;
			}
		}
		
		keyBindingMain = new KeyBinding("key.presencefootsteps", keyBindDefaultCode, "key.categories.misc");
		Minecraft.getMinecraft().gameSettings.keyBindings = ArrayUtils.addAll(Minecraft.getMinecraft().gameSettings.keyBindings, keyBindingMain);
		keyBindingMain.setKeyCode(getConfig().getInteger("key.code"));
		KeyBinding.resetKeyBindingArrayAndHash();
		
		watcher.add(keyBindingMain);
		keyManager.addKeyBinding(keyBindingMain, new Ha3KeyHolding(this, 7));
		
		// Hooking
		IResourceManager resMan = Minecraft.getMinecraft().getResourceManager();
		if (resMan instanceof IReloadableResourceManager) {
			((IReloadableResourceManager) resMan).registerReloadListener(this);
		}
		
		((OperatorCaster) op()).setTickEnabled(true);
		((OperatorCaster) op()).setFrameEnabled(true);
	}
	
	public void reloadEverything(boolean nested) {
		isolator = new PFIsolator(this);
		
		reloadConfig();
		
		List<ResourcePackRepository.Entry> repo = dealer.findResourcePacks();
		if (repo.size() == 0)
		{
			PFLog.log("Presence Footsteps didn't find any compatible resource pack.");
			hasResourcePacks = false;
			hasDisabledResourcePacks = dealer.findDisabledResourcePacks().size() > 0;
			
			isolator.setGenerator(null);
			
			return;
		}
		hasResourcePacks = true;
		hasDisabledResourcePacks = false;
		
		for (ResourcePackRepository.Entry pack : repo) {
			PFLog.debug("Will load: " + pack.getResourcePackName());
		}
		
		reloadBlockMap(repo);
		reloadPrimitiveMap(repo);
		reloadAcoustics(repo);
		isolator.setSolver(new PFSolver(isolator));
		reloadVariator(repo);
		
		int stance = getConfig().getInteger("custom.stance");
		boolean mlpMode = stance == 1 || (stance == 0 && mlpInstalled);
		isolator.setGenerator(mlpMode ? new PFReaderQP(isolator, util()) : new PFReaderH(isolator, util()));
	}
	
	private void reloadConfig() {
		config = new ConfigProperty();
		updateNotifier.fillDefaults(config);
		config.setProperty("user.volume", 70);
		config.setProperty("mlp.detected", false);
		config.setProperty("custom.stance", 0);
		config.setProperty("key.code", keyBindDefaultCode);
		config.setProperty("user.enabled", true);
		config.commit();
		
		boolean fileExisted = new File(presenceDir, "userconfig.cfg").exists();
		
		try {
			config.setSource(new File(presenceDir, "userconfig.cfg").getCanonicalPath());
			config.load();
		} catch (IOException e) {
			e.printStackTrace();
			throw new RuntimeException("Error caused config not to work: " + e.getMessage());
		}
		
		if (!fileExisted) {
			config.save();
		}
		
		updateNotifier.loadConfig(config);
	}
	
	private void reloadVariator(List<ResourcePackRepository.Entry> repo) {
		Variator var = new NormalVariator();
		
		int working = 0;
		for (ResourcePackRepository.Entry pack : repo) {
			try {
				InputStreamConfigProperty config = new InputStreamConfigProperty();
				config.loadStream(dealer.openVariator(pack.getResourcePack()));
				
				var.loadConfig(config);
				working = working + 1;
			} catch (Exception e) {
				PFLog.debug("No variator found in " + pack.getResourcePackName() + ": " + e.getMessage());
			}
		}
		if (working == 0) {
			PFLog.log("No variators found in " + repo.size() + " packs!");
		}
		
		isolator.setVariator(var);
	}
	
	private void reloadBlockMap(List<ResourcePackRepository.Entry> repo) {
		BlockMap blockMap = new LegacyCapableBlockMap();
		
		int working = 0;
		for (ResourcePackRepository.Entry pack : repo) {
			try {
				InputStreamConfigProperty blockSound = new InputStreamConfigProperty();
				blockSound.loadStream(dealer.openBlockMap(pack.getResourcePack()));
				
				new BlockMapReader().setup(blockSound, blockMap);
				working++;
			} catch (IOException e) {
				PFLog.debug("No blockmap found in " + pack.getResourcePackName() + ": " + e.getMessage());
			}
		}
		if (working == 0) {
			PFLog.log("No blockmaps found in " + repo.size() + " packs!");
		}
		
		isolator.setBlockMap(blockMap);
	}
	
	private void reloadPrimitiveMap(List<ResourcePackRepository.Entry> repo) {
		PrimitiveMap primitiveMap = new BasicPrimitiveMap();
		
		int working = 0;
		for (ResourcePackRepository.Entry pack : repo) {
			try {
				InputStreamConfigProperty primitiveSound = new InputStreamConfigProperty();
				primitiveSound.loadStream(dealer.openPrimitiveMap(pack.getResourcePack()));
				new PrimitiveMapReader().setup(primitiveSound, primitiveMap);
				working = working + 1;
			} catch (IOException e) {
				PFLog.debug("No primitivemap found in " + pack.getResourcePackName() + ": " + e.getMessage());
			}
		}
		if (working == 0) {
			PFLog.log("No blockmaps found in " + repo.size() + " packs!");
		}
		
		isolator.setPrimitiveMap(primitiveMap);
	}
	
	private void reloadAcoustics(List<ResourcePackRepository.Entry> repo) {
		AcousticsManager acoustics = new AcousticsManager(this.isolator);
		
		int working = 0;
		Scanner scanner = null;
		for (ResourcePackRepository.Entry pack : repo) {
			try {
				scanner = new Scanner(dealer.openAcoustics(pack.getResourcePack()));
				String jasonString = scanner.useDelimiter("\\Z").next();
				new AcousticsJsonReader("").parseJSON(jasonString, acoustics);
				working = working + 1;
			} catch (IOException e) {
				PFLog.debug("No acoustics found in " + pack.getResourcePackName() + ": " + e.getMessage());
			} finally {
				if (scanner != null) scanner.close();
			}
		}
		if (working == 0) {
			PFLog.log("No blockmaps found in " + repo.size() + " packs!");
		}
		
		isolator.setAcoustics(acoustics);
		isolator.setSoundPlayer(new UserConfigSoundPlayerWrapper(acoustics, config));
		isolator.setDefaultStepPlayer(acoustics);
	}
	
	public boolean mlpInstalled() {
		return mlpInstalled;
	}
	
	private boolean isInstalledMLP() {
		return Ha3StaticUtilities.classExists("com.minelittlepony.minelp.Pony", this);
	}
	
	public boolean getEnabled() {
		return enabled;
	}
	
	public int getVolume() {
		int result = getConfig().getInteger("user.volume");
		if (result < 0) return 0;
		if (result > 100) return 100;
		return result;
	}
	
	public void setVolume(int volume) {
		getConfig().setProperty("user.volume", volume);
	}
	
	public boolean toggle() {
		config.setProperty("user.enabled", enabled = !enabled);
		saveConfig();
		if (enabled) {
			reloadEverything(false);
		} else {
			isolator = new PFIsolator(this);
			reloadConfig();
			isolator.setGenerator(null);
			setPlayerStepDistance(0);
		}
		return enabled;
	}
	
	private void setPlayerStepDistance(int value) {
		try {
			util().setPrivate(Minecraft.getMinecraft().thePlayer, "Entity_nextStepDistance", value); //nextStepDistance
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
	
	@Override
	public void onTick() {
		if (tickRound == 0) {
			int keyCode = keyBindingMain.getKeyCode();
			if (keyCode != config.getInteger("key.code")) {
				PFLog.log("Key binding changed. Saving...");
				config.setProperty("key.code", keyCode);
				saveConfig();
			}
		}
		watcher.onTick();
		keyManager.handleRuntime();
		tickRound = (tickRound + 1) % 100;
	}
	
	@Override
	public void onFrame(float semi) {
		if (Minecraft.getMinecraft().thePlayer == null) return;
		
		boolean keysDown = util().areKeysDown(Keyboard.KEY_LCONTROL, Keyboard.KEY_LSHIFT, Keyboard.KEY_F); 
		debugButton.signalState(keysDown);
		if (keysDown && System.currentTimeMillis() - pressedOptionsTime > 1000) {
			debugState = false;
			displayMenu();
		}
		
		if (enabled && hasResourcePacks) {
			isolator.onFrame();
			setPlayerStepDistance(Integer.MAX_VALUE);
		}
		
		if (!firstTickPassed) {
			firstTickPassed = true;
			updateNotifier.attempt();
			if (mlpDetectedFirst) {
				chatter.printChat(EnumChatFormatting.AQUA, I18n.format("pf.mlp.0"));
				chatter.printChatShort(I18n.format("pf.mlp.1"));
				if (getConfig().getInteger("custom.stance") == 0) {
					chatter.printChatShort(EnumChatFormatting.GRAY, I18n.format(keyBindingMain.getKeyCode() == 0 ? "pf.mlp.2.stance" : "pf.mlp.2.stance.button", Keyboard.getKeyName(keyBindingMain.getKeyCode())));
				} else {
					chatter.printChatShort(EnumChatFormatting.GRAY, I18n.format(keyBindingMain.getKeyCode() == 0 ? "pf.mlp.2" : "pf.mlp.2.button", Keyboard.getKeyName(keyBindingMain.getKeyCode())));
				}
			}
			
			if (!hasResourcePacks) {
				hasResourcePacks_FixMe = true;
				if (hasDisabledResourcePacks) {
					chatter.printChat(EnumChatFormatting.RED, I18n.format("pf.pack.disabled.0"));
					chatter.printChatShort(EnumChatFormatting.WHITE, I18n.format("pf.pack.disabled.1"));
				} else {
					chatter.printChat(EnumChatFormatting.RED, I18n.format("pf.pack.0"));
					chatter.printChatShort(EnumChatFormatting.WHITE, I18n.format("pf.pack.1"));
				}
			}
		}
		if (hasResourcePacks_FixMe && hasResourcePacks) {
			hasResourcePacks_FixMe = false;
			chatter.printChat(EnumChatFormatting.GREEN, I18n.format("pf.pack.yay"));
		}
	}
	
	private void displayMenu() {
		if (util().isCurrentScreen(null)) {
			Minecraft mc = Minecraft.getMinecraft();
			mc.displayGuiScreen(new PFGuiMenu((GuiScreen) util().getCurrentScreen(), this));
			if (mc.isSingleplayer() && !mc.getIntegratedServer().getPublic()) {
                mc.getSoundHandler().pauseSounds();
            }
			PFLog.setDebugEnabled(false);
		}
	}
	
	public boolean hasResourcePacksLoaded() {
		return hasResourcePacks;
	}
	
	public boolean hasResourcePacksInstalled() {
		return hasDisabledResourcePacks;
	}
	
	public PFIsolator getIsolator() {
		return isolator;
	}
	
	@Override
	public ConfigProperty getConfig() {
		return config;
	}
	
	@Override
	public Chatter getChatter() {
		return chatter;
	}
	
	@Override
	public Identity getIdentity() {
		return identity;
	}
	
	@Override
	public void saveConfig() {
		if (config.commit()) { // If there were changes...
			PFLog.log("Saving configuration...");
			config.save(); // Write changes on disk.
		}
	}
	
	@Override
	public void onResourceManagerReload(IResourceManager var1) {
		PFLog.log("Resource Pack reload detected...");
		reloadEverything(false);
	}
	
	@Override
	public void onKey(KeyBinding event) {
		keyManager.handleKeyDown(event);
	}
	
	@Override
	public void beginPress() {
		displayMenu();
	}
	
	@Override
	public void endPress() {}
	
	@Override
	public void shortPress() {}
	
	@Override
	public void beginHold() {}
	
	@Override
	public void endHold() {}
}

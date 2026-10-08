package com.terraformersmc.modmenu;

import com.google.common.collect.LinkedListMultimap;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Multimaps;
import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import com.terraformersmc.modmenu.api.UpdateChecker;
import com.terraformersmc.modmenu.config.ModMenuConfig;
import com.terraformersmc.modmenu.config.ModMenuConfigManager;
import com.terraformersmc.modmenu.gui.ModMenuOptionsScreen;
import com.terraformersmc.modmenu.util.EnumToLowerCaseJsonConverter;
import com.terraformersmc.modmenu.util.ModMenuScreenTexts;
import com.terraformersmc.modmenu.util.NullScreenFactory;
import com.terraformersmc.modmenu.util.UpdateCheckerUtil;
import com.terraformersmc.modmenu.util.mod.Mod;
import com.terraformersmc.modmenu.util.mod.neoforge.NeoForgeMod;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.NumberFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@net.neoforged.fml.common.Mod(value = ModMenu.MOD_ID, dist = Dist.CLIENT)
public class ModMenu {
    public static final String MOD_ID = "modmenu";
    public static final String GITHUB_REF = "TerraformersMC/ModMenu";
    public static final Logger LOGGER = LoggerFactory.getLogger("Mod Menu");
    public static final Gson GSON;
    public static final Gson GSON_MINIFIED;

    static {
        GsonBuilder builder = new GsonBuilder().registerTypeHierarchyAdapter(Enum.class, new EnumToLowerCaseJsonConverter()).setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES);
        GSON = builder.setPrettyPrinting().create();
        GSON_MINIFIED = builder.create();
    }

    public static final Map<String, Mod> MODS = new ConcurrentHashMap<>();
    public static final Map<String, Mod> ROOT_MODS = new ConcurrentHashMap<>();
    public static final ListMultimap<Mod, Mod> PARENT_MAP = Multimaps.synchronizedListMultimap(LinkedListMultimap.create());

    private static final Map<String, IConfigScreenFactory> configScreenFactories = new ConcurrentHashMap<>();

    private static int cachedDisplayedModCount = -1;
    public static final boolean RUNNING_QUILT = false;
    public static final boolean DEV_ENVIRONMENT = Boolean.getBoolean("modmenu.development");
    public static final boolean TEXT_PLACEHOLDER_COMPAT = false;

    public static boolean hasConfigScreen(String modId) {
        return getConfigScreenFactory(modId) != null;
    }

    public static @Nullable Screen getConfigScreen(String modId, Screen parent) {
        IConfigScreenFactory factory = getConfigScreenFactory(modId);
        if (factory != null) {
            return factory.createScreen(ModList.get().getModContainerById(modId).orElseThrow(), parent);
        } else {
            return null;
        }
    }

    private static @Nullable IConfigScreenFactory getConfigScreenFactory(String modId) {
        if (ModMenuConfig.HIDDEN_CONFIGS.getValue().contains(modId)) {
            return null;
        }

        return configScreenFactories.computeIfAbsent(modId, id -> ModList.get().getModContainerById(id)
                .flatMap(container -> IConfigScreenFactory.getForMod(container.getModInfo())).orElse(null));
    }

    public ModMenu(IEventBus modBus, ModContainer self) {
        ModMenuConfigManager.initializeConfig();
        modBus.addListener(com.terraformersmc.modmenu.event.ModMenuEventHandler::registerKeys);
        configScreenFactories.put(MOD_ID, (container, parent) -> new ModMenuOptionsScreen(parent));
        configScreenFactories.put("minecraft", (container, parent) -> new OptionsScreen(parent,
                Minecraft.getInstance().options, Minecraft.getInstance().level != null));
        for (ModContainer modContainer : ModList.get().getSortedMods()) {
            Mod mod = new NeoForgeMod(modContainer);
            MODS.put(mod.getId(), mod);
            IConfigScreenFactory.getForMod(modContainer.getModInfo()).ifPresent(factory -> configScreenFactories.put(mod.getId(), factory));
        }

        checkForUpdates();

        // Initialize parent map
        HashSet<String> modParentSet = new HashSet<>();
        for (Mod mod : MODS.values()) {
            String parentId = mod.getParent();
            if (parentId == null) {
                ROOT_MODS.put(mod.getId(), mod);
                continue;
            }

            Mod parent;
            modParentSet.clear();
            while (true) {
                parent = MODS.get(parentId);

                parentId = parent != null ? parent.getParent() : null;
                if (parentId == null) {
                    // It will most likely end here in the first iteration
                    break;
                }

                if (modParentSet.contains(parentId)) {
                    LOGGER.warn("Mods contain each other as parents: {}", modParentSet);
                    parent = null;
                    break;
                }

                modParentSet.add(parentId);
            }

            if (parent == null) {
                ROOT_MODS.put(mod.getId(), mod);
                continue;
            }

            PARENT_MAP.put(parent, mod);
        }

    }

    public static void clearModCountCache() {
        cachedDisplayedModCount = -1;
    }

    public static void checkForUpdates() {
        UpdateCheckerUtil.checkForUpdates();
    }

    public static boolean areModUpdatesAvailable() {
        if (!ModMenuConfig.UPDATE_CHECKER.getValue()) {
            return false;
        }

        for (Mod mod : MODS.values()) {
            if (mod.isHidden()) {
                continue;
            }

            if (ModMenuConfig.SHOW_LIBRARIES.getValue().hideMod(mod, getConfigScreenFactory(mod.getId()) != null)) {
                continue;
            }

            if (mod.hasUpdate() || mod.getChildHasUpdate()) {
                return true; // At least one currently visible mod has an update
            }
        }

        return false;
    }

    public static String getDisplayedModCount() {
        if (cachedDisplayedModCount == -1) {
            boolean includeChildren = ModMenuConfig.COUNT_CHILDREN.getValue();
            boolean includeLibraries = ModMenuConfig.COUNT_LIBRARIES.getValue();
            boolean includeHidden = ModMenuConfig.COUNT_HIDDEN_MODS.getValue();

            // listen, if you have >= 2^32 mods then that's on you
            cachedDisplayedModCount = Math.toIntExact(MODS.values().stream().filter(mod -> {
                boolean isChild = mod.getParent() != null;
                if (!includeChildren && isChild) {
                    return false;
                }

                boolean isLibrary = mod.getBadges().contains(Mod.Badge.LIBRARY);
                if (!includeLibraries && isLibrary) {
                    return false;
                }

                return includeHidden || !mod.isHidden();
            }).count());
        }

        return NumberFormat.getInstance().format(cachedDisplayedModCount);
    }

    public static Component createModsButtonText(boolean title) {
        var titleStyle = ModMenuConfig.MODS_BUTTON_STYLE.getValue();
        var gameMenuStyle = ModMenuConfig.GAME_MENU_BUTTON_STYLE.getValue();
        var isIcon = title ?
                titleStyle == ModMenuConfig.TitleMenuButtonStyle.ICON :
                gameMenuStyle == ModMenuConfig.GameMenuButtonStyle.ICON;
        var isShort = title ?
                titleStyle == ModMenuConfig.TitleMenuButtonStyle.SHRINK :
                gameMenuStyle == ModMenuConfig.GameMenuButtonStyle.ICON;
        MutableComponent modsText = ModMenuScreenTexts.TITLE.copy();
        if (ModMenuConfig.MOD_COUNT_LOCATION.getValue().isOnModsButton() && !isIcon) {
            String count = ModMenu.getDisplayedModCount();
            if (isShort) {
                modsText.append(Component.literal(" ")).append(Component.translatable("modmenu.loaded.short", count));
            } else {
                String specificKey = "modmenu.loaded." + count;
                String key = Language.getInstance().has(specificKey) ? specificKey : "modmenu.loaded";
                if (ModMenuConfig.EASTER_EGGS.getValue() && Language.getInstance().has(specificKey + ".secret")) {
                    key = specificKey + ".secret";
                }
                modsText.append(Component.literal(" ")).append(Component.translatable(key, count));
            }
        }
        return modsText;
    }
}

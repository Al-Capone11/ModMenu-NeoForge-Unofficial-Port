package com.terraformersmc.modmenu.util.mod.neoforge;

import com.google.common.collect.Sets;
import com.terraformersmc.modmenu.ModMenu;
import com.terraformersmc.modmenu.api.UpdateChecker;
import com.terraformersmc.modmenu.api.UpdateInfo;
import com.terraformersmc.modmenu.config.ModMenuConfig;
import com.terraformersmc.modmenu.util.VersionUtil;
import com.terraformersmc.modmenu.util.mod.Mod;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.moddiscovery.ModFileInfo;
import net.neoforged.neoforge.client.gui.modlist.DefaultModDisplayInfo;
import net.neoforged.neoforge.client.gui.modlist.ImageResource;
import net.neoforged.neoforge.client.gui.modlist.ModDisplayInfo;
import net.neoforged.neoforgespi.locating.IModFile;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/** Adapter from NeoForge mod metadata to Mod Menu's platform-neutral model. */
public class NeoForgeMod implements Mod {
    protected final ModContainer container;
    protected final Set<Badge> badges = new HashSet<>();
    protected final Map<String, String> links = new HashMap<>();
    private final Map<String, String> contacts = new HashMap<>();
    private final List<String> authors;
    private final String id, name, description, version, parent;
    private final List<ImageResource> iconResources;
    private final Set<String> license;
    private boolean childHasUpdate;
    private UpdateChecker updateChecker;
    private UpdateInfo updateInfo;

    public NeoForgeMod(ModContainer container) {
        this.container = container;
        var info = container.getModInfo();
        id = info.getModId();
        name = info.getDisplayName();
        description = info.getDescription();
        version = info.getVersion().toString();
        authors = info.getConfig().<String>getConfigElement("authors").map(s -> Arrays.stream(s.split(",\\s*")).filter(v -> !v.isBlank()).toList()).orElse(List.of());
        license = info.getOwningFile().getLicense().isBlank() ? Set.of() : Set.of(info.getOwningFile().getLicense());
        badges.addAll(Badge.convert(getStringSet("badges"), id));
        String website = getString("displayURL");
        String issues = getString("issueTrackerURL");
        if (website != null) links.put("homepage", website);
        if (issues != null) links.put("issues", issues);
        Object parentValue = getModMenuProperty("parent");
        String metadataParent = parentValue instanceof String s && !s.isBlank() ? s : null;
        List<ModContainer> jarAncestors = findJarParentContainers();
        String jarParent = jarAncestors.isEmpty() ? null : jarAncestors.getFirst().getModId();
        parent = metadataParent != null ? metadataParent : jarParent;
        ModDisplayInfo displayInfo = container.getCustomExtension(ModDisplayInfo.class)
                .orElseGet(() -> new DefaultModDisplayInfo(container));
        if ("minecraft".equals(id)) badges.add(Badge.MINECRAFT);
        if ("java".equals(id) || id.endsWith("_api") || id.contains("library")) badges.add(Badge.LIBRARY);

        LinkedHashSet<ImageResource> images = new LinkedHashSet<>();
        addContainerImageCandidates(container, displayInfo, images);
        for (ModContainer ancestor : jarAncestors) {
            ModDisplayInfo ancestorDisplay = ancestor.getCustomExtension(ModDisplayInfo.class)
                    .orElseGet(() -> new DefaultModDisplayInfo(ancestor));
            addContainerImageCandidates(ancestor, ancestorDisplay, images);
        }
        iconResources = List.copyOf(images);
    }

    private static void addContainerImageCandidates(ModContainer container, ModDisplayInfo displayInfo,
                                                     Set<ImageResource> images) {
        String modId = container.getModId();
        DefaultModDisplayInfo defaultDisplay = new DefaultModDisplayInfo(container);
        if (displayInfo.icon() != null) images.add(displayInfo.icon());
        addMetadataImageCandidates(container, defaultDisplay, "iconFile", images);
        images.add(defaultDisplay.convertPath("assets/" + modId + "/icon.png"));
        if (displayInfo.banner() != null) images.add(displayInfo.banner());
        addMetadataImageCandidates(container, defaultDisplay, "bannerFile", images);
        addMetadataImageCandidates(container, defaultDisplay, "logoFile", images);
    }

    private static void addMetadataImageCandidates(ModContainer container, DefaultModDisplayInfo display,
                                                    String key, Set<ImageResource> images) {
        var info = container.getModInfo();
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        info.getConfig().<String>getConfigElement(key).ifPresent(paths::add);
        info.getOwningFile().getConfig().<String>getConfigElement(key).ifPresent(paths::add);
        if ("logoFile".equals(key)) info.getLogoFile().ifPresent(paths::add);

        String modId = container.getModId();
        for (String path : paths) {
            if (path.isBlank()) continue;
            images.add(display.convertPath(path));
            String dottedAssetsPath = expandDottedAssetsPath(path);
            if (dottedAssetsPath != null) images.add(display.convertPath(dottedAssetsPath));

            // Some mods set logoFile/iconFile to a bare filename while storing the
            // image under their normal assets namespace instead of the mod jar root.
            String normalizedPath = path.replace('\\', '/');
            String fileName = normalizedPath.substring(normalizedPath.lastIndexOf('/') + 1);
            if (fileName.isBlank()) continue;
            images.add(display.convertPath("assets/" + modId + "/" + fileName));
            images.add(display.convertPath("assets/" + modId + "/textures/gui/" + fileName));
            images.add(display.convertPath("assets/" + modId + "/textures/" + fileName));
        }
    }

    @Nullable
    private static String expandDottedAssetsPath(String path) {
        if (!path.startsWith("assets.") || path.contains("/") || path.contains("\\")) return null;
        String assetPath = path.substring("assets.".length());
        int extensionSeparator = assetPath.lastIndexOf('.');
        if (extensionSeparator <= 0) return null;
        String extension = assetPath.substring(extensionSeparator + 1);
        if (!Set.of("png", "jpg", "jpeg", "webp").contains(extension.toLowerCase(Locale.ROOT))) return null;
        String pathWithoutExtension = assetPath.substring(0, extensionSeparator).replace('.', '/');
        return "assets/" + pathWithoutExtension + "." + extension;
    }

    private List<ModContainer> findJarParentContainers() {
        IModFile currentFile = ((ModFileInfo) container.getModInfo().getOwningFile()).getFile();
        Set<String> visitedFiles = new HashSet<>();
        Set<String> visitedMods = new HashSet<>();
        List<ModContainer> ancestors = new ArrayList<>();
        while (currentFile != null && visitedFiles.add(currentFile.getFilePath().toString())) {
            IModFile parentFile = currentFile.getDiscoveryAttributes().parent();
            if (parentFile == null) break;
            for (var parentInfo : parentFile.getModInfos()) {
                String candidateId = parentInfo.getModId();
                if (id.equals(candidateId) || !visitedMods.add(candidateId)) continue;
                ModList.get().getModContainerById(candidateId).ifPresent(ancestors::add);
            }
            currentFile = parentFile;
        }
        return ancestors;
    }

    @SuppressWarnings("unchecked")
    private Object getModMenuProperty(String key) {
        try {
            Object properties = ((ModFileInfo) container.getModInfo().getOwningFile()).getConfigElement("modproperties", "modmenu").orElse(null);
            return properties instanceof Map<?, ?> map ? map.get(key) : null;
        } catch (RuntimeException ignored) { return null; }
    }

    private String getString(String key) {
        return container.getModInfo().getConfig().<String>getConfigElement(key).orElse(null);
    }
    private Set<String> getStringSet(String key) {
        Object value = getModMenuProperty(key);
        if (value instanceof Collection<?> values) {
            Set<String> result = new LinkedHashSet<>();
            values.stream().filter(String.class::isInstance).map(String.class::cast).forEach(result::add);
            return result;
        }
        return Set.of();
    }

    @Override public String getId() { return id; }
    @Override public String getName() { return name; }
    @Override public DynamicTexture getIcon(NeoForgeIconHandler handler, int size) {
        DynamicTexture texture = handler.createIcon(id, iconResources);
        if (texture == null) texture = handler.createIcon("modmenu", "assets/modmenu/unknown_icon.png");
        return Objects.requireNonNull(texture, "Mod Menu fallback icon is missing");
    }
    @Override public String getDescription() { return description == null ? "" : description; }
    @Override public String getVersion() { return version; }
    @Override public String getPrefixedVersion() { return VersionUtil.getPrefixedVersion(version); }
    @Override public List<String> getAuthors() { return authors; }
    @Override public Map<String, String> getContact(String author) { return contacts; }
    @Override public Map<String, Collection<String>> getContributors() { return Map.of(); }
    @Override public SortedMap<String, Set<String>> getCredits() {
        SortedMap<String, Set<String>> result = new TreeMap<>();
        if (!authors.isEmpty()) result.put("Author", new LinkedHashSet<>(authors));
        return result;
    }
    @Override public Set<Badge> getBadges() { return badges; }
    @Override public @Nullable String getWebsite() { return links.get("homepage"); }
    @Override public @Nullable String getIssueTracker() { return links.get("issues"); }
    @Override public @Nullable String getSource() { return links.get("sources"); }
    @Override public @Nullable String getParent() { return parent; }
    @Override public Set<String> getLicense() { return license; }
    @Override public Map<String, String> getLinks() { return links; }
    @Override public boolean isReal() { return true; }
    @Override public boolean allowsUpdateChecks() { return !ModMenuConfig.DISABLE_UPDATE_CHECKER.getValue().contains(id); }
    @Override public @Nullable UpdateChecker getUpdateChecker() { return updateChecker; }
    @Override public void setUpdateChecker(@Nullable UpdateChecker checker) { updateChecker = checker; }
    @Override public @Nullable UpdateInfo getUpdateInfo() { return updateInfo; }
    @Override public void setUpdateInfo(@Nullable UpdateInfo info) { updateInfo = info; }
    @Override public void setChildHasUpdate() { childHasUpdate = true; }
    @Override public boolean getChildHasUpdate() { return childHasUpdate; }
    @Override public boolean isHidden() { return ModMenuConfig.HIDDEN_MODS.getValue().contains(id); }
}

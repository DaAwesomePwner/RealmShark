package assets;

import assets.resextractor.UnityExtractor;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.swing.filechooser.FileSystemView;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import realmshark.branding.AppIdentity;
import util.PropertiesManager;

/**
 * Main loader for assets. If assets are missing or are outdated,
 * extracts the assets from realm resources files.
 */
public class AssetExtractor {

    public static final String ASSETS_OBJECT_FILE_DIR_PATH =
        "assets/ObjectID.list";
    public static final String ASSETS_TILE_FILE_DIR_PATH = "assets/TileID.list";
    private static String REALM_RES_PATH;
    private static boolean explicitPath;
    private static volatile java.util.function.Consumer<String> progress = text -> {};

    static {
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("mac")) {
            REALM_RES_PATH =
                System.getProperty("user.home") +
                    "/.local/share/RealmOfTheMadGod/Production/RotMGExalt.app/Contents/Resources/Data/resources.assets";
        } else if (os.contains("win")) {
            String localAppData = System.getenv("LOCALAPPDATA");
            if (localAppData != null) {
                REALM_RES_PATH =
                        localAppData +
                                "/RealmOfTheMadGod/Production/RotMG Exalt_Data/resources.assets";

                // Fallback for older installations
                File testFile = new File(REALM_RES_PATH);
                if (!testFile.exists()) {
                    REALM_RES_PATH =
                            "RealmOfTheMadGod/Production/RotMG Exalt_Data/resources.assets";
                }
            }
        } else {
            REALM_RES_PATH =
                System.getenv("LOCALAPPDATA") +
                "/RealmOfTheMadGod/Production/RotMG Exalt_Data/resources.assets";
        }
    }

    public static void main(String[] args) throws Throwable {
        AppIdentity.initialize();
        extractAssetsFromXML(AssetCache.root());
    }

    /**
     * Sets the resource path for finding where the game assets are found
     * @param path Path to the realm resource file.
     */
    public static void setRealmResPath(String path) {
        REALM_RES_PATH = path;
        explicitPath = true;
    }

    /**
     * Main loader for realm assets.
     */
    public static void checkForExtraction(String version) throws Throwable {
        if (needsExtraction(version)) recover(assetFile(), version, text -> {});
    }

    public static boolean hasUsableCache() {
        return hasUsableCache(AssetCache.root());
    }

    private static boolean hasUsableCache(Path root) {
        for (String name : new String[]{"ObjectID.list", "TileID.list", "xml/equip.xml", "xml/players.xml", "xml/enchantments.xml"})
            if (!Files.isRegularFile(root.resolve(name)) || !Files.isReadable(root.resolve(name))) return false;
        return true;
    }

    public static boolean needsExtraction(String version) throws IOException {
        if (!hasUsableCache()) return true;
        File source = assetFile();
        return source != null && checkUpdateAssets(lastEdited(version)) != 0;
    }

    /** Runs on the setup worker, with capture stopped. No Swing or native capture is started here. */
    public static void recover(File source, String version, java.util.function.Consumer<String> listener)
            throws IOException, ParserConfigurationException {
        recover(source, version, listener, (input, output) -> new UnityExtractor().extract(input, output));
    }

    @FunctionalInterface interface Extraction { void extract(File source, File[] folders) throws IOException; }

    static synchronized void recover(File source, String version, java.util.function.Consumer<String> listener, Extraction extraction)
            throws IOException, ParserConfigurationException {
        if (source == null || !source.isFile() || !source.canRead()) throw new IOException("Choose a readable resources.assets file.");
        progress = listener == null ? text -> {} : listener;
        Path generation = null;
        boolean published = false;
        try {
            String stamp = Files.getLastModifiedTime(source.toPath()).toString() + "-" + version;
            generation = AssetCache.createGeneration();
            File[] folders = {generation.resolve("flatbuffer").toFile(), generation.resolve("sprites").toFile(), generation.resolve("xml").toFile()};
            extraction.extract(source, folders);
            extractAssetsFromXML(generation);
            if (!hasUsableCache(generation)) throw new IOException("Extraction did not produce the required asset files.");
            Runnable catalogs = prepareCatalogs(generation);
            setDisplay("Publishing validated assets");
            AssetCache.publish(generation, stamp, catalogs);
            published = true;
            SpriteJson.jsonFileReader();
            setRealmResPath(source.getAbsolutePath());
            PropertiesManager.setProperties("realmResPath", source.getAbsolutePath());
            PropertiesManager.setProperties("lastModifiedTime", stamp);
        } finally {
            progress = text -> {};
            if (generation != null && !published) AssetCache.discard(generation);
        }
    }

    public static String lastEdited(String version) throws IOException {
        File file = assetFile();
        if (file == null) throw new IOException("Game asset source not found. Choose resources.assets to recover.");
        BasicFileAttributes attr;
        attr = Files.readAttributes(file.toPath(), BasicFileAttributes.class);
        return attr.lastModifiedTime().toString() + "-" + version;
    }

    public static void setDisplay(String s) {
        progress.accept(s);
    }

    /**
     * Uses custom path to resources.assets file. If no custom path is found
     * finds Windows path to realm resources.assets file.
     *
     * @return Absolute path to resources.assets file.
     */
    public static File assetFile() {
        File defaultFile;
        try {
            String configured = explicitPath ? REALM_RES_PATH : PropertiesManager.getProperty("realmResPath");
            if (configured == null || configured.trim().isEmpty()) configured = REALM_RES_PATH;
            if (configured == null || configured.trim().isEmpty()) return null;
            if (Paths.get(configured).isAbsolute()) {
                defaultFile = new File(configured);
            } else {
                String homeDir = FileSystemView.getFileSystemView().getDefaultDirectory().getAbsolutePath();
                Path defaultPath = Paths.get(homeDir, configured);
                defaultFile = defaultPath.toFile();
            }
        } catch (java.nio.file.InvalidPathException e) {
            return null;
        }
        if (defaultFile.isFile()) {
            return defaultFile;
        }
        return null;
    }

    /**
     * Checks if asset folders exist and current build version is matching.
     *
     * @param lastModifiedTime Last modified time of the assets file.
     * @return True if assets are missing.
     */
    private static int checkUpdateAssets(String lastModifiedTime) {
        if (!Files.isRegularFile(AssetCache.path("ObjectID.list"))) return 1;
        if (!Files.isRegularFile(AssetCache.path("TileID.list"))) return 2;
        if (
            !Objects.equals(
                AssetCache.stamp() == null ? PropertiesManager.getProperty("lastModifiedTime") : AssetCache.stamp(),
                lastModifiedTime
            )
        ) return 3;
        return 0;
    }

    /**
     * Extracts assets from XML files.
     */
    private static void extractAssetsFromXML(Path root)
        throws IOException, ParserConfigurationException {
        ArrayList<AssetObject> objectAssets = new ArrayList<>();
        ArrayList<AssetTile> tileAssets = new ArrayList<>();
        ArrayList<Path> files = new ArrayList<>();

        try (Stream<Path> paths = Files.walk(root.resolve("xml"))) {
            paths.filter(Files::isRegularFile).filter(p -> p.toString().endsWith("xml")).forEach(files::add);
        }

        int counter = 0;
        for (Path p : files) {
            counter++;
            AssetExtractor.setDisplay("Parsing XML Files " + counter);
            try {
                parseXML(p, objectAssets, tileAssets);
            } catch (SAXException e) { throw new IOException("An extracted XML file could not be parsed.", e); }
        }

        objectAssets.sort(Comparator.comparing(a -> a.id));
        writeAssetList(root.resolve("ObjectID.list"), objectAssets.stream().map(Object::toString).collect(Collectors.toList()));

        tileAssets.sort(Comparator.comparing(a -> a.id));
        writeAssetList(root.resolve("TileID.list"), tileAssets.stream().map(Object::toString).collect(Collectors.toList()));
    }

    private static void writeAssetList(Path target, List<String> lines) throws IOException {
        Path temporary = Files.createTempFile(target.toAbsolutePath().getParent(), "asset-list-", ".tmp");
        try {
            Files.write(temporary, lines, java.nio.charset.Charset.defaultCharset());
            try { Files.move(temporary, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE); }
            catch (java.nio.file.AtomicMoveNotSupportedException e) { Files.move(temporary, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }

    /**
     * Reloads assets to reset assets in running app.
     */
    public static synchronized void reloadAssetsOnRunningApp() throws IOException {
        Runnable ready = prepareCatalogs(AssetCache.root());
        synchronized (ImageBuffer.class) { ready.run(); }
        SpriteJson.jsonFileReader();
    }

    private static Runnable prepareCatalogs(Path root) throws IOException {
        List<Runnable> prepared = new ArrayList<>();
        prepared.add(tomato.gui.myinfo.Equip.prepareReload(root.resolve("xml/equip.xml")));
        prepared.add(tomato.realmshark.ParseEquipment.prepareReload(root.resolve("xml/equip.xml")));
        prepared.add(tomato.realmshark.enums.CharacterClass.prepareReload(root.resolve("xml/players.xml")));
        prepared.add(tomato.realmshark.ParseEnchants.prepareReload(root.resolve("xml/enchantments.xml")));
        prepared.add(IdToAsset.prepareReload(root));
        prepared.add(tomato.realmshark.ParseDungeon.prepareReload(root.resolve("xml")));
        try { prepared.add(tomato.backend.data.AbilityScalingManager.getInstance().prepareReload(root.resolve("xml/equip.xml"))); }
        catch (Exception failure) { throw new IOException("Could not load ability scaling definitions.", failure); }
        prepared.add(SpriteFlatBuffer.prepareReload(root.resolve("flatbuffer/spritesheetf")));
        return () -> { prepared.forEach(Runnable::run); ImageBuffer.clear(); };
    }

    /**
     * Creates a Document object from XML files given by path.
     *
     * @param path Path to a XML file.
     * @return Returns Document object.
     */
    private static Document getDocumentElement(Path path)
        throws ParserConfigurationException, SAXException, IOException {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        DocumentBuilder db = dbf.newDocumentBuilder();
        db.setErrorHandler(new IgnoreErrorHandler());
        Document doc = db.parse(new File(path.toAbsolutePath().toString()));
        doc.getDocumentElement().normalize();
        return doc;
    }

    /**
     * Root method for parsing XML files.
     *
     * @param path         Path to an XML file.
     * @param objectAssets Object assets array to store parsed XML objects into.
     * @param tileAssets   Tile asset array to st store parsed XML tiles into.
     */
    private static void parseXML(
        Path path,
        ArrayList<AssetObject> objectAssets,
        ArrayList<AssetTile> tileAssets
    ) throws ParserConfigurationException, IOException, SAXException {
        Document doc = getDocumentElement(path);

        NodeList listObjects = doc.getElementsByTagName("Object");
        for (int i = 0; i < listObjects.getLength(); i++) {
            Node node = listObjects.item(i);
            AssetObject ao = new AssetObject();

            if (node.hasAttributes()) {
                addAttributes(node, ao);
            }

            parseChildObjects(node, ao);
            objectAssets.add(ao);
        }

        NodeList listTiles = doc.getElementsByTagName("Ground");
        for (int i = 0; i < listTiles.getLength(); i++) {
            Node node = listTiles.item(i);
            AssetTile at = new AssetTile();

            if (node.hasAttributes()) {
                addAttributes(node, at);
            }

            parseChildTiles(node, at);
            tileAssets.add(at);
        }
    }

    /**
     * Parses attributes from XML objects
     *
     * @param node Node of parse attributes
     * @param ao   Asset objects to store the parsed data into.
     */
    private static void addAttributes(Node node, Asset ao) {
        NamedNodeMap attribNode = node.getAttributes();
        for (int j = 0; j < attribNode.getLength(); j++) {
            Node item = attribNode.item(j);
            String name = item.getNodeName();
            String value = item.getNodeValue();

            if (name.equals("id")) {
                ao.idName = value;
            } else if (name.equals("type")) {
                ao.id = Integer.decode(value);
            }
        }
    }

    /**
     * Node parsing method to iterate over the sibling nodes and parse using lambdas.
     *
     * @param node The node to be parsed.
     * @param al   Asset lambda object for pasing the node data.
     */
    public static void addNode(Node node, AssetLambda al) {
        Node n = node.getFirstChild();
        while (n != null) {
            String name = n.getNodeName();
            String value = "";
            if (n.hasChildNodes()) value = n.getFirstChild().getNodeValue();

            al.parse(name, value, n);

            n = n.getNextSibling();
        }
    }

    /**
     * Child nodes of object data to parse.
     *
     * @param node The node to be parsed.
     * @param ao   Asset objects to store the parsed data into.
     */
    private static void parseChildObjects(Node node, AssetObject ao) {
        addNode(node, (name, value, n) -> {
            switch (name) {
                case "DisplayId":
                    ao.display = value;
                    break;
                case "Class":
                    ao.clazz = value;
                    break;
                case "Group":
                    ao.group = value;
                    break;
                case "Labels":
                    ao.labels = value;
                    break;
                case "Tier":
                    ao.tier = value;
                    break;
                case "SlotType":
                    ao.slotType = value;
                    break;
                case "Subattack":
                    addSubattack(n, ao);
                    break;
                case "Projectile":
                    addProjectile(n, ao);
                    break;
                case "Texture":
                case "AnimatedTexture":
                    addTexture(n, ao);
                    break;
            }
        });
    }

    /**
     * Child nodes of tile data to parse.
     *
     * @param node The node to be parsed.
     * @param at   Asset objects to store the parsed data into.
     */
    private static void parseChildTiles(Node node, AssetTile at) {
        AssetDamage damage = new AssetDamage();
        addNode(node, (name, value, n) -> {
            switch (name) {
                case "Texture":
                    addTexture(n, at);
                    break;
                case "RandomTexture":
                    if (n.hasChildNodes()) {
                        parseChildTiles(n, at);
                    }
                    break;
                case "MinDamage":
                    if (n.hasChildNodes()) {
                        damage.min = value;
                    }
                case "MaxDamage":
                    if (n.hasChildNodes()) {
                        damage.max = value;
                    }
                    break;
            }
        });
        if (damage.min != null && damage.max != null) {
            at.damage = damage;
        }
    }

    /**
     * Subattack to be parsed.
     *
     * @param node The node to be parsed.
     * @param ao   Asset objects to store the parsed data into.
     */
    private static void addSubattack(Node node, AssetObject ao) {
        NamedNodeMap attribNode = node.getAttributes();
        for (int j = 0; j < attribNode.getLength(); j++) {
            Node item = attribNode.item(j);
            String name = item.getNodeName();
            String value = item.getNodeValue();

            if (name.equals("projectileId")) {
                int i = Integer.parseInt(value);
                ao.subattack.add(i);
            }
        }
    }

    /**
     * Projectile nodes of the main object data to be parsed.
     *
     * @param node The node to be parsed.
     * @param ao   Asset objects to store the parsed data into.
     */
    private static void addProjectile(Node node, AssetObject ao) {
        AssetProjectile projectile = new AssetProjectile();
        addNode(node, (name, value, n) -> {
            switch (name) {
                case "Damage":
                    projectile.min = value;
                    projectile.max = value;
                    break;
                case "MinDamage":
                    projectile.min = value;
                    break;
                case "MaxDamage":
                    projectile.max = value;
                    break;
                case "ArmorPiercing":
                    projectile.peirce = true;
                    break;
            }
        });
        if (projectile.min != null && projectile.max != null) {
            if (ao.projectiles == null) ao.projectiles = new ArrayList<>();
            ao.projectiles.add(projectile);
        }
    }

    /**
     * Texture nodes of the main object data to be parsed.
     *
     * @param node The node to be parsed.
     * @param ao   Asset objects to store the parsed data into.
     */
    private static void addTexture(Node node, Asset ao) {
        AssetTexture texture = new AssetTexture();
        addNode(node, (name, value, n) -> {
            switch (name) {
                case "Index":
                    texture.index = value.startsWith("0x")
                        ? Integer.decode(value)
                        : Integer.parseInt(value);
                    break;
                case "File":
                    texture.file = value;
                    break;
            }
        });
        if (texture.file != null && texture.index != -1) {
            if (ao.textures == null) ao.textures = new ArrayList<>();
            ao.textures.add(texture);
        }
    }

    /**
     * Lambda function used to parse the XML data.
     */
    private interface AssetLambda {
        void parse(String name, String a, Node n);
    }

    /**
     * Superclass to store XML parsed object and tile data into.
     */
    private static class Asset {

        int id;
        String idName = "";

        ArrayList<AssetTexture> textures;
    }

    /**
     * Class to store XML parsed object data into.
     */
    private static class AssetObject extends Asset {

        String display = "";
        String clazz = "";
        String group = "";
        String labels = "";
        String tier = "";
        String slotType = "";

        ArrayList<AssetProjectile> projectiles;
        ArrayList<Integer> subattack = new ArrayList<>();

        @Override
        public String toString() {
            //            if (clazz != null && clazz.equals("Equipment") && labels.contains("UT")) {
            //                idName = "UT " + idName;
            //            } else if (clazz != null && clazz.equals("Equipment") && !tier.equals("")) {
            //                idName = "T" + tier + " " + idName;
            //            }

            StringBuilder projectileString = new StringBuilder();
            if (projectiles != null) {
                projectileString.append(slotType + ",");
                if (subattack.size() == 0) {
                    for (AssetProjectile p : projectiles) {
                        projectileString.append(p);
                    }
                } else {
                    for (int i : subattack) {
                        projectileString.append(projectiles.get(i));
                    }
                }
                projectileString.deleteCharAt(projectileString.length() - 1);
            }

            StringBuilder textureString = new StringBuilder();
            if (textures != null) {
                for (AssetTexture p : textures) {
                    textureString.append(p);
                }
                textureString.deleteCharAt(textureString.length() - 1);
            }

            return String.format(
                "%s;%s;%s;%s;%s;%s;%s;%s",
                id,
                display,
                clazz,
                group,
                projectileString,
                textureString,
                labels,
                idName
            );
        }
    }

    /**
     * Class to store XML parsed tile data into.
     */
    private static class AssetTile extends Asset {

        AssetDamage damage;

        @Override
        public String toString() {
            StringBuilder damageString = new StringBuilder();
            if (damage != null) {
                damageString.append(damage.min).append(",").append(damage.max);
            }

            StringBuilder textureString = new StringBuilder();
            if (textures != null) {
                for (AssetTexture p : textures) {
                    textureString.append(p);
                }
                textureString.deleteCharAt(textureString.length() - 1);
            }
            return String.format(
                "%d;%s;%s;%s",
                id,
                textureString,
                damageString,
                idName
            );
        }
    }

    /**
     * Class to store XML parsed projectile data into.
     */
    private static class AssetProjectile {

        String min;
        String max;
        boolean peirce = false;

        @Override
        public String toString() {
            return (
                min.replaceAll("\t", "") +
                "," +
                max.replaceAll("\t", "") +
                "," +
                (peirce ? "1," : "0,")
            );
        }
    }

    /**
     * Class to store XML parsed damage data into.
     */
    private static class AssetDamage {

        String min;
        String max;

        @Override
        public String toString() {
            return min + "," + max;
        }
    }

    /**
     * Class to store XML parsed texture/sprite data into.
     */
    private static class AssetTexture {

        int index = -1;
        String file;

        @Override
        public String toString() {
            return index + "," + file + ",";
        }
    }

    private static class IgnoreErrorHandler implements ErrorHandler {

        @Override
        public void warning(SAXParseException exception) throws SAXException {}

        @Override
        public void error(SAXParseException exception) throws SAXException {}

        @Override
        public void fatalError(SAXParseException exception)
            throws SAXException {}
    }
}

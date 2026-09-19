package tomato.realmshark;

/**
 * Reviewed fallback for portable installs without extracted assets. Labels and hexadecimal
 * portal object types are transcribed from assets/xml (reviewed September 2026): portals.xml,
 * the per-dungeon *Objects.xml files listed below, objects.xml and tutorial_objects.xml.
 * DungeonPortal and dungeonTemplates.xml support content classification, but neither a
 * DungeonName nor a flag alone overrides the reviewed hub/tutorial/test exceptions.
 * Aliases are exact DungeonName/DisplayId pairs, except the explicitly reviewed short labels
 * Realm and Bazaar. Guild Hall 2-4 come from objects.xml GuildItemParam; Tutorial is the
 * standard introductory map. -1 means there is no verified portal ID for that label.
 * Keep distinct encounters distinct; do not derive map aliases from object IDs.
 */
final class BundledAreaCatalog {
    private BundledAreaCatalog() { }

    static void populate(DungeonCatalog c) {
        // portals.xml: normal dungeon portals, including legacy and seasonal combat content.
        c.addReviewed("Sprite World", 0x070c, true);
        c.addReviewed("Pirate Cave", 0x0717, true);
        c.addReviewed("Forest Maze", 0x5f34, true);
        c.addReviewed("Snake Pit", 0x0718, true);
        c.addReviewed("Spider Den", 0x0719, true);
        c.addReviewed("Undead Lair", 0x071a, true);
        c.addReviewed("Abyss of Demons", 0x071b, true);
        c.addReviewed("Mad Lab", 0x0890, true);
        c.addReviewed("Oryx's Castle", 0x0d89, true);
        c.addReviewed("Oryx's Chamber", 0x0634, true);
        c.addReviewed("Chicken Chamber", 0x0bb7, true);
        c.addReviewed("Wine Cellar", 0x0242, true);
        c.addReviewed("Wine Cellar Short", 0x6020, true);
        c.addReviewed("Battle for the Nexus", 0x075e, true);
        c.addReviewed("Dreamscape Labyrinth", 0x0735, true);
        c.addReviewed("Tomb of the Ancients", 0x0734, true);
        c.addReviewed("Tomb of the Ancients (Heroic)", 0x076a, true);
        c.addReviewed("Belladonna's Garden", 0x2291, true);
        c.addReviewed("Legacy Lair of Shaitan", 0x2295, true, "Legacy Shaitan's Portal");
        c.addReviewed("Ocean Trench", 0x0730, true);
        c.addReviewed("Forbidden Jungle", 0x0733, true);
        c.addReviewed("Manor of the Immortals", 0x0739, true);
        c.addReviewed("Davy Jones' Locker", 0x0741, true);
        c.addReviewed("Beachzone", 0x0742, true);
        c.addReviewed("Cave of a Thousand Treasures", 0x5e2e, true, "Cave of A Thousand Treasures");
        c.addReviewed("Candyland Hunting Grounds", 0x074a, true);
        c.addReviewed("Haunted Cemetery", 0x074b, true);
        c.addReviewed("Haunted Cemetery Gates", 0x5f25, true, "Gates");
        c.addReviewed("Haunted Cemetery Graves", 0x5f26, true, "Graves");
        c.addReviewed("Haunted Cemetery Final Battle", 0x5f29, true, "Final Battle");
        c.addReviewed("Halloween Haunted Cemetery", 0x0135, true);
        c.addReviewed("Puppet Master's Theatre", 0x2353, true);
        c.addReviewed("The Shatters", 0x727e, true);
        c.addReviewed("Puppet Master's Encore", 0x7466, true);
        c.addReviewed("Ice Citadel", 0x9cfd, true);
        c.addReviewed("Ice Cave", 0x748b, true);
        c.addReviewed("The Inner Sanctum", 0x748d, true);
        c.addReviewed("Legacy Lair of Draconis", 0x753e, true);
        c.addReviewed("The Ivory Wyvern", 0x754e, true);
        c.addReviewed("Consolation of Draconis", 0x753f, true);
        c.addReviewed("Woodland Labyrinth", 0x075c, true);
        c.addReviewed("Deadwater Docks", 0x075d, true);
        c.addReviewed("Legacy Bilgewater's Grotto", 0x708b, true, "Bilgewater's Grotto");
        c.addReviewed("The Crawling Depths", 0x072e, true);
        c.addReviewed("Toxic Sewers", 0x023e, true);
        c.addReviewed("The Hive", 0x011d, true);
        c.addReviewed("Mountain Temple", 0x0137, true);
        c.addReviewed("Legacy Heroic Undead Lair", 0x246b, true);
        c.addReviewed("Legacy Heroic Abyss of Demons", 0x246c, true);
        c.addReviewed("Lair of Draconis", 0xb217, true);
        c.addReviewed("Lair of Shaitan", 0x6d99, true);
        c.addReviewed("The Third Dimension", 0x4b66, true);
        c.addReviewed("Crab Arena", 0x65db, true);
        c.addReviewed("Thessal Arena", 0x65de, true);
        c.addReviewed("Xolotl Arena", 0x660f, true);
        c.addReviewed("Puppet Arena", 0x6610, true);
        c.addReviewed("Crab Arena 2", 0x0529, true);
        c.addReviewed("Thessal Arena 2", 0x052a, true);
        c.addReviewed("Xolotl Arena 2", 0x052b, true);
        c.addReviewed("Puppet Arena 2", 0x052c, true);
        c.addReviewed("The Bridge Sentinel Rehearsal", 0x3a23, true);
        c.addReviewed("The Twilight Archmage Rehearsal", 0x3a24, true);
        c.addReviewed("The Forgotten King Rehearsal", 0x3a25, true);
        c.addReviewed("Hidden Interregnum", 0xc268, true);
        c.addReviewed("Sulfurous Wetlands", 0x6392, true);
        c.addReviewed("Queen Bunny Chamber", 0x0596, true);
        c.addReviewed("Spectral Penitentiary", 0x5c8b, true);
        c.addReviewed("Cnidaria Arena", 0xcb87, true);
        c.addReviewed("Cnidaria Arena 2", 0xcb88, true);
        c.addReviewed("The Shatters Arena", 0xcb89, true);
        c.addReviewed("The Shatters Arena 2", 0xcb8a, true);
        c.addReviewed("Stromwell's Rift I", 0x3321, true);
        c.addReviewed("Stromwell's Rift II", 0x3322, true);
        c.addReviewed("Stromwell's Rift III", 0x3323, true);
        c.addReviewed("Legacy Pirate Cave", 0xcf22, true);
        c.addReviewed("Legacy Spider Den", 0xcf23, true);
        c.addReviewed("Legacy Undead Lair", 0xcf24, true);
        c.addReviewed("Legacy Abyss of Demons", 0xcf25, true);
        c.addReviewed("Legacy Forest Maze", 0xcf26, true);
        c.addReviewed("Legacy Sprite World", 0xcf27, true);
        c.addReviewed("Legacy The Shatters", 0xcf28, true);
        c.addReviewed("Legacy Deadwater Docks", 0xcf29, true);
        c.addReviewed("Legacy The Crawling Depths", 0xcf2a, true);
        c.addReviewed("Legacy Woodland Labyrinth", 0xcf2b, true);
        c.addReviewed("Time Chamber", 0xcfbd, true);

        // Separate dungeon XML: lostHalls, fungalCavern, crystalCave, epicHive, steamworks,
        // oryxSanctuary, moonlightVillage, ancientRuins, magicWoods, cursedLibrary,
        // parasiteDen, cnidarianReef, secludedThicket, highTechTerror and iceTomb.xml.
        c.addReviewed("Lost Halls", 0xb024, true);
        c.addReviewed("The Void", 0xb013, true);
        c.addReviewed("Remnant of the Void", 0x099e, true);
        c.addReviewed("Cultist Hideout", 0xb063, true);
        c.addReviewed("Fungal Cavern", 0xb26f, true);
        c.addReviewed("Crystal Cavern", 0x273a, true);
        c.addReviewed("The Nest", 0x10a3, true);
        c.addReviewed("Plagued Nest", 0x44a2, true);
        c.addReviewed("Kogbold Steamworks", 0xc119, true);
        c.addReviewed("Advanced Kogbold Steamworks", 0x7096, true);
        c.addReviewed("Oryx's Sanctuary", 0x184a, true);
        c.addReviewed("Oryx Mania Sanctuary", 0x0771, true);
        c.addReviewed("Moonlight Village", 0x4fdf, true);
        c.addReviewed("Ancient Ruins", 0x25b9, true);
        c.addReviewed("Magic Woods", 0x087c, true);
        c.addReviewed("Cursed Library", 0xab56, true);
        c.addReviewed("Parasite Chambers", 0x0798, true);
        c.addReviewed("Cnidarian Reef", 0x09fa, true);
        c.addReviewed("Secluded Thicket", 0x369f, true);
        c.addReviewed("High Tech Terror", 0x3d72, true);
        c.addReviewed("Ice Tomb", 0x7fb8, true);

        // alienInvasion, theMachine, innerWorkings, rollerRink, abyssOfDemons, undeadLair,
        // cronusTrials, battleOryx, santaWorkshop, stPatricks, oryxHorde, eventChest, whiteSnake.
        c.addReviewed("Malogia", 0xb2b8, true);
        c.addReviewed("Neo Malogia", 0xdc61, true);
        c.addReviewed("Untaris", 0xb2b7, true);
        c.addReviewed("Neo Untaris", 0xdc62, true);
        c.addReviewed("Forax", 0xb2cb, true);
        c.addReviewed("Neo Forax", 0xdc63, true);
        c.addReviewed("Katalund", 0xb2ce, true);
        c.addReviewed("Neo Katalund", 0xdc64, true);
        c.addReviewed("The Machine", 0xabd2, true);
        c.addReviewed("The Inner Workings", 0xc500, true);
        c.addReviewed("The Tavern", 0x4550, true);
        c.addReviewed("Infernal Abyss of Demons", 0x3a0d, true);
        c.addReviewed("Heroic Undead Lair", 0x3962, true);
        c.addReviewed("The Trials of Cronus", 0x332c, true, "mgm2 Dungeon");
        c.addReviewed("Mad God Mayhem", 0x0f21, true, "Oryx Pandemonium Decaract");
        c.addReviewed("Santa's Workshop", 0x3cce, true, "Santa Workshop");
        c.addReviewed("Rainbow Road", 0x1648, true);
        c.addReviewed("Oryx Horde", 0x1fe4, true, "OryxHorde");
        c.addReviewed("Oryxmania Treasure Room", 0x02fb, true);
        c.addReviewed("Oryxmania Treasure Room 2", 0x040e, true);
        c.addReviewed("White Snake Invasion I", 0xe9ee, true);
        c.addReviewed("White Snake Invasion II", 0xe9ef, true);
        c.addReviewed("White Snake Invasion III", 0xe9f0, true);

        // Hubs/overworld: objects.xml, interregnumNexusObjects.xml, tutorial_objects.xml.
        c.addReviewed("Realm of the Mad God", 0x0704, false, "Realm");
        c.addReviewed("Random Realm", 0x071c, false);
        c.addReviewed("Nexus", 0x071d, false);
        c.addReviewed("Vault", 0x0720, false);
        c.addReviewed("Guild Hall", 0x072f, false);
        c.addReviewed("Guild Hall 2", -1, false);
        c.addReviewed("Guild Hall 3", -1, false);
        c.addReviewed("Guild Hall 4", -1, false);
        c.addReviewed("Grand Bazaar", 0x0750, false, "Cloth Bazaar", "Bazaar");
        c.addReviewed("Pet Yard", 0x0753, false);
        c.addReviewed("Daily Login Room", 0x175c, false);
        c.addReviewed("Daily Quest Room", 0x1756, false);
        c.addReviewed("Interregnum Daily Quest Room", 0xcebe, false);
        c.addReviewed("Court of Oryx", 0x2001, false);
        c.addReviewed("Tutorial", -1, false);
        c.addReviewed("Kitchen", 0x071e, false);
        c.addReviewed("Exalted Kitchen", 0x027b, false);
        c.addReviewed("Nexus Explanation", 0x0746, false);
        c.addReviewed("Vault Explanation", 0x0747, false);
        c.addReviewed("Guild Explanation", 0x0748, false);

        // Reviewed test/encounter-development maps and minigames, not regular dungeon runs.
        // Sources: portals, lostHalls, lairOfDraconis, crystalCave, cronusTrials,
        // permaFrostLord.xml, tTesting.xml, rollerRink, innerWorkings and whiteSnake.
        c.addReviewed("Admin Arena", 0x65da, false);
        c.addReviewed("LOD Rock Dragon", 0xb1c4, false, "Rock Dragon Test");
        c.addReviewed("LH Test", 0xb0b4, false, "Lost Halls (Test)");
        c.addReviewed("LH Boss Test", 0xb03e, false, "Lost Halls (Boss)");
        c.addReviewed("GC encounter", 0x6501, false);
        c.addReviewed("KSW Encounter", 0x74e0, false);
        c.addReviewed("RRCubeGodTest", 0x562c, false);
        c.addReviewed("FR Test", 0x1aa1, false);
        c.addReviewed("DPS Test", 0xe266, false);
        c.addReviewed("Beer Encounter Arena", 0x45fd, false);
        c.addReviewed("Chess", 0xaada, false);
        c.addReviewed("EdenDayMosaic", 0xea0b, false);
        c.addReviewed("EdenNightMosaic", 0xea0c, false);
        c.addReviewed("WhiteSnakeTest", 0xea0d, false);
        c.addReviewed("Easter Treasure Rooms Test Maps", 0x958f, false);
        c.addReviewed("Snowball Rolling (Map 1)", 0x0f84, false, "Snowball Rolling Solo");
        c.addReviewed("Snowball Rolling (Map 2)", 0x0f83, false, "Snowball Rolling Solo 2");

        // Shared DisplayId values in rollerRinkObjects, portals, oryxSanctuaryObjects and eventChestObjects.
        // Exact DungeonName still resolves; display-only observations cannot distinguish them.
        c.addAmbiguousDisplays("The Tavern", "The Shatters", "Oryx's Sanctuary", "Mysterious Arena", "Treasure Room");
    }
}

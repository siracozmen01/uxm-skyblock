package com.uxplima.uxmskyblock.core.domain.mission;

public enum MissionTriggerType {
    BLOCK_BREAK,
    BLOCK_PLACE,
    CROP_HARVEST,
    MOB_KILL,
    CUSTOM_MOB_KILL,
    FISHING,
    LEVEL_REACH,
    BANK_BALANCE,
    ITEM_SUBMIT,
    /** The OneBlock island's own block is broken; the target is the phase it was broken in. */
    ONEBLOCK_BREAK,
    /** A OneBlock island reaches a phase; the target is the phase begun. */
    ONEBLOCK_PHASE,
    /** A Boxed island's box grows; the amount is how far. */
    BOXED_GROW,
    /** Items crafted at a table or in the inventory; the target is what was made. */
    CRAFT,
    /** Items taken smelted out of a furnace; the target is what was taken. */
    SMELT,
    /** An item enchanted at an enchanting table; the target is the item. */
    ENCHANT,
    /** Potions brewed at a stand the player opened; the target is the potion's kind. */
    BREW,
    /** Experience gained; the amount is how many points. */
    EXPERIENCE,
    /** A mission of several lines; it counts the lines finished, and nothing fires it directly. */
    LINES
}

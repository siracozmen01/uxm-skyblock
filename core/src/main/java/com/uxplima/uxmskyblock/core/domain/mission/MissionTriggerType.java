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
    BOXED_GROW
}

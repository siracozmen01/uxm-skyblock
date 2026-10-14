package com.uxplima.uxmskyblock.core.application.oneblock;

import java.util.Optional;

/** What one break draws, drawn ahead of it. */
record DrawnAhead(long at, String block, Optional<String> creature) {}

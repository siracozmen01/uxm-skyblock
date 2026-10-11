# uxm-skyblock

High-performance, distributed Skyblock core engine for Paper and Folia.

## Architecture

The project is structured following clean architecture / hexagonal principles:

- **`api`**: Public integration API, events, and service interfaces for external plugins.
- **`core`**: Pure domain models, business use cases, coordinate math, and authority rules (no Minecraft server dependencies).
- **`persistence-adapter`**: Relational storage adapters (SQLite, MySQL, PostgreSQL) with automated schema migrations partitioned into `CoreSchemaMigrations`, `GameplaySchemaMigrations`, and `OperationsSchemaMigrations`.
- **`bukkit-adapter`**: Paper / Folia platform adapter, custom event listeners, GUIs, commands, and modular bootstrap wirings (`ConfigurationWiring`, `PersistenceWiring`, `IntegrationWiring`, and `GameplayWiring` with dedicated sub-wirings `AdminWiring`, `EconomicWiring`, and `SocialWiring`).
- **`rest-adapter`**: Standalone embedded HTTP service for external status monitoring and cluster queries.

## Requirements

- **Java**: 25 or higher
- **Server**: Paper 26.2+ or Folia 1.21+

## Commands

- **`/is`**: Core island operations: `/is create`, `/is home`, `/is sethome`, `/is info`, `/is members`, `/is invite`, `/is kick`, `/is leave`, `/is disband`, `/is level`, `/is top`, `/is settings`.
- **`/is bank`**: Island economic banking: `/is bank balance`, `/is bank status`, `/is bank upkeep`, `/is bank paydebt`, `/is bank deposit <amount>`, `/is bank withdraw <amount>`.
- **`/is chat`**: Private island communication: `/is chat [message]`, `/is c [message]`, `/is chat spy`.
- **`/is admin`**: Staff oversight & disaster recovery: `/is admin inactivity`, `/is admin freeze <islandId>`, `/is admin unfreeze <islandId>`, `/is admin inspect <islandId>`, `/is admin restore <backupId>`, `/is admin delete <islandId>`.

## Building

```bash
./gradlew build -Puxm.composite=false
```

## Testing

```bash
./gradlew test -Puxm.composite=false
```

## License

This project is licensed under the GNU General Public License v3.0 - see the [LICENSE](LICENSE) file for details.

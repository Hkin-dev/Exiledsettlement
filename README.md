# Exiled Settlement 放逐聚落

Build and manage a survival settlement in Minecraft. Residents gather supplies, carry materials, construct buildings, and work in production facilities in the game world.

在 Minecraft 世界里建立和经营聚落，让居民实际领取物资、搬运材料、建造房屋，并参与生产。

**Early public test version. Back up your world before installing or updating.**

**早期公开测试版，安装或更新前请备份存档。**

## Requirements

| Component | Version |
| --- | --- |
| Minecraft Java Edition | 1.21.1 |
| Loader | NeoForge 21.1.235 or later within the 21.1 series |
| Java | 21 |
| Mod | 0.17.2 |

YSM and YSM Mapping API are optional. The mod uses its built-in resident models without them. Resident YSM appearance support activates when both compatible components are installed. Third-party models and compatibility mods are not included in this repository.

YSM 和 YSM Mapping API 为可选兼容。未安装时使用内置居民模型；两项兼容组件同时安装后才启用对应外观功能。第三方模型和兼容模组不随本项目分发。

## Features

- Establish a starting camp with residents and a finite supply of materials.
- Plan housing and facilities, then let residents collect materials and build them.
- Manage resident jobs and physical storage and delivery.
- Develop farms, forestry, mining, and building-material production.
- Build several housing tiers and a 20-bed apartment building.
- Save settlement, resident, building, and inventory state in the world.

- 使用开局营地蓝图建立聚落，获得首批居民与有限初始物资。
- 规划住宅和生产设施，由居民实际领料施工。
- 管理居民职业、公共库存与物资搬运。
- 发展农业、林业、采矿和建筑材料生产。
- 建造多档住宅与二十床公寓。
- 将聚落、居民、建筑及库存状态保存在世界中。

## Design Philosophy · 设计理念

Exiled Settlement aims to make a settlement's growth visible in the Minecraft world. These principles guide its ongoing development:

- **Residents are at the heart of the settlement.** Homes, meals, tools, and daily routines give planning a purpose. A growing population brings both more workers and more people to support.
- **Progress comes from visible work.** Gathering supplies, carrying materials, and building step by step connect the settlement's growth to what its residents do in the world.
- **Layout is part of management.** The distance between homes, workplaces, and storage affects time spent walking and hauling. Terrain, entrances, and roads deserve as much thought as the buildings themselves.
- **Expansion calls for preparation.** Finite starting supplies are a foundation for a lasting production system. Food, fuel, tools, and building materials need to support daily life as well as population growth and changing seasons.
- **The player sets the direction and can lend a hand.** Choose what to build, where to build it, and how to assign work. Residents handle routine labor, while you can gather supplies, build, and help the settlement through shortages.
- **Development belongs in the Minecraft world.** Buildings and work should fit the terrain, paths, and structures around them. Respecting player construction and edits is part of that design.

《放逐聚落》希望让玩家在 Minecraft 世界中亲眼看见聚落逐渐成长。以下理念指导本项目的持续开发：

- **以居民的生活为中心。** 住房、饮食、工具与日常作息赋予规划具体的意义。增加人口，既意味着更多劳动力，也意味着更多需要供养的人。
- **让发展来自看得见的劳动。** 从领取物资、搬运材料到逐步施工，让聚落的变化与居民在世界中的行动联系起来。
- **布局本身就是经营。** 住宅、工作地与仓储之间的距离影响行走和搬运所需的时间。地形、入口与道路，同样值得认真规划。
- **为扩张提前做准备。** 有限的开局物资用于建立持续运转的生产体系。食物、燃料、工具和建材既要满足日常生活，也要为人口增长与季节变化留出余量。
- **玩家决定方向，也能亲手参与。** 玩家决定建什么、建在哪里，以及如何分配职业；居民承担日常劳动。玩家也可以亲自采集、建设和补充物资，帮助聚落渡过短缺。
- **让聚落融入 Minecraft 世界。** 建筑与居民工作应与周围的地形、通道和既有建筑相协调，并尊重玩家亲手建造与修改的内容。

## Installation and first steps

1. Install Minecraft 1.21.1 with the required NeoForge version and Java 21.
2. Download the mod `.jar` from [Releases](https://github.com/HkinXu/Exiledsettlement/releases) and place it in the instance's `mods` folder. Remove any other active version of this mod.
3. Restart Minecraft and start a new world for your first test.
4. Eligible players receive a starting camp blueprint on entering the world. Use it on the ground, choose a location, and confirm the camp.
5. Open the settlement core to manage residents and plan construction. Initial supplies are finite and need replenishment as the settlement grows.

1. 安装 Minecraft 1.21.1、所需版本的 NeoForge 与 Java 21。
2. 从 [Releases 下载页面](https://github.com/HkinXu/Exiledsettlement/releases)下载模组 `.jar`，放入游戏实例的 `mods` 文件夹，移出本模组其他启用版本。
3. 完整重启游戏，首次体验建议使用新世界。
4. 尚未使用开局资格的玩家进入世界后会获得营地蓝图。手持蓝图右键地面，选择位置并确认营地。
5. 通过聚落核心管理居民和规划建设。初始库存有限，随着聚落发展需要继续补充物资。

For multiplayer, install the same mod version and compatible NeoForge on the server and participating clients.

联机时，服务端和参与的客户端均安装相同版本的本模组及兼容的 NeoForge。

## Build from source

See [BUILDING.md](BUILDING.md) for the Java 21 and Gradle build instructions.

源码构建方法见 [BUILDING.md](BUILDING.md)。

## Feedback

Use [Issues](https://github.com/HkinXu/Exiledsettlement/issues) to report problems. Include the mod version, Minecraft and NeoForge versions, reproduction steps, and relevant logs with private information removed.

问题反馈请使用 [Issues](https://github.com/HkinXu/Exiledsettlement/issues)，附上模组、Minecraft 和 NeoForge 版本、复现步骤及已移除私人信息的相关日志。

## AI assistance

AI tools have contributed substantially to implementation and documentation under the maintainer's direction. The project also includes AI-assisted artwork. Gameplay decisions, in-game building edits, and release decisions are directed by the maintainer.

本项目在维护者指导下使用 AI 工具辅助完成了较多程序实现和文档工作，也包含 AI 辅助美术。玩法决策、游戏内建筑修改和版本发布由维护者主导。

## License

Original project code and content are released under the [MIT License](LICENSE). Third-party components retain their own terms; see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

本项目原创代码和内容采用 [MIT 许可证](LICENSE)。第三方组件仍遵守各自许可，详见 [第三方声明](THIRD_PARTY_NOTICES.md)。

Not an official Minecraft product. Not approved by or associated with Mojang or Microsoft.

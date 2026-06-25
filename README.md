# ItemLock

ItemLock 是一个面向 Spigot / Paper / Folia 的物品灵魂绑定插件。插件使用
`PersistentDataContainer` 把绑定状态写入物品本身，并用异步 YAML 存储维护绑定记录，
适合用于服务器装备、工具、稀有物品的归属保护。

## 功能

- 绑定物品所有者，并在 lore 中显示灵魂绑定信息。
- 绑定卷轴支持“待绑定”流程：物品先被标记，第一次实际使用后才写入主人。
- 解绑卷轴支持移除已绑定或待绑定状态。
- 阻止玩家拿取、移动、使用其他玩家已经绑定的物品。
- 可配置是否禁止丢出已绑定物品、漏斗移动、死亡掉落。
- 支持按行为完成绑定：破坏方块、穿戴护甲、击杀实体、直接交互。
- 支持 Spigot / Paper 主线程调度，并兼容 Folia 的区域调度。
- 支持 Paper RGB / legacy `&` 颜色文本。

## 运行环境

- Minecraft 服务端：Spigot / Paper 1.16.5+
- Folia：支持，`plugin.yml` 已声明 `folia-supported: true`
- Java：8+
- 构建工具：Maven

## 安装

1. 下载或构建插件 jar。
2. 将 jar 放入服务端 `plugins/` 目录。
3. 启动服务器生成默认配置。
4. 按需修改：
   - `plugins/ItemLock/config.yml`
   - `plugins/ItemLock/binding.yml`
   - `plugins/ItemLock/lang/zh_cn.yml`
5. 执行 `/itemlock reload` 或重启服务器。

## 构建

```bash
mvn clean package
```

构建产物位于 `target/itemlock-1.0-SNAPSHOT.jar`。

## 命令

主命令别名：`/itemlock`、`/ilock`

| 命令 | 权限 | 说明 |
| --- | --- | --- |
| `/itemlock bind` | `itemlock.bind` | 将手中物品标记为待绑定，实际使用后绑定主人 |
| `/itemlock unbind` | `itemlock.unbind` | 解除手中物品的绑定或待绑定状态 |
| `/itemlock scroll [bind|unbind] [数量] [玩家]` | `itemlock.scroll` | 给予绑定卷轴或解绑卷轴 |
| `/itemlock reload` | `itemlock.reload` | 异步重载配置和语言文件 |

默认所有命令权限均为 OP。

## 权限

| 权限 | 默认 | 说明 |
| --- | --- | --- |
| `itemlock.command` | OP | 使用 ItemLock 主命令 |
| `itemlock.bind` | OP | 使用绑定命令 |
| `itemlock.unbind` | OP | 使用解绑命令 |
| `itemlock.scroll` | OP | 生成绑定/解绑卷轴 |
| `itemlock.reload` | OP | 重载配置 |
| `itemlock.bypass` | OP | 绕过他人物品保护 |

## 绑定流程

普通绑定不会立即写入主人：

1. 使用 `/itemlock bind`，或把绑定卷轴覆盖到目标物品上。
2. 目标物品进入“待灵魂绑定”状态。
3. 玩家第一次实际使用该物品后，插件写入该玩家的 UUID 和名称。
4. 物品变为真正的灵魂绑定物品。

待绑定物品不属于任何玩家，因此可以丢出、拾取和转交。只有完成绑定后，外人才会被阻止拿取、移动或使用。

## 配置

### `config.yml`

核心开关：

```yaml
binding:
  automatic: false
  bind-already-bound-items: false
  detect-owner-lore: true
  owner-lore-format: "&#BFC7D5◆ &#7AD7FF灵魂绑定 &#6B7280· &#FFFFFF %player%"

unbind:
  owner-only-scroll: true

protection:
  cancel-drop: true
  cancel-hopper-move: true
  block-foreign-move: true
  block-foreign-use: true
  keep-bound-on-death: true
```

- `binding.automatic`：是否自动把符合规则的物品标记为待绑定。默认关闭。
- `binding.bind-already-bound-items`：是否允许覆盖已绑定物品的主人。
- `binding.detect-owner-lore`：没有插件 PDC 绑定标记时，是否从 lore 中识别绑定所有者。
- `binding.owner-lore-format`：lore 识别格式，必须包含 `%player%` 作为玩家名占位符。
- `unbind.owner-only-scroll`：解绑卷轴是否只允许所有者使用。
- `protection.cancel-drop`：是否禁止丢出真正已绑定的物品。
- `protection.cancel-hopper-move`：是否禁止漏斗等容器移动已绑定或待绑定物品。
- `protection.block-foreign-move`：是否禁止移动他人已绑定物品。
- `protection.block-foreign-use`：是否禁止使用他人已绑定物品。
- `protection.keep-bound-on-death`：死亡时是否保留自己的已绑定物品。

卷轴物品可通过 `bind-scroll` 和 `unbind-scroll` 配置材料、自定义模型数据和名称匹配。

### `binding.yml`

`binding.yml` 控制哪些物品会在哪些行为后完成绑定：

```yaml
actions:
  block-break:
    types:
      - PICKAXES
      - AXES
      - SHOVELS
      - HOES
      - SHEARS

  armor-equip:
    types:
      - ARMOR
      - ELYTRA
      - SHIELD

  kill:
    types:
      - WEAPONS

  interact:
    types:
      - INTERACT_TOOLS
      - MISC_ITEMS
```

每个行为都支持：

- `types`：内置分类，如 `WEAPONS`、`ARMOR`、`PICKAXES`、`TOOLS`。
- `materials`：额外指定 Bukkit Material。
- `excluded-materials`：排除指定 Bukkit Material。

## 数据文件

绑定记录保存在：

```text
plugins/ItemLock/item-lock-data.yml
```

物品本身也会写入 PDC 标记，所以即使物品被移动到背包、箱子或掉落物中，绑定状态仍随物品保留。

## 开发说明

- 主包名：`ym.itemlock`
- 插件入口：`ym.itemlock.bootstrap.ItemLockPlugin`
- 主要模块：
  - `command`：命令与补全
  - `config`：配置解析
  - `lang`：语言与颜色文本
  - `listener`：Bukkit 事件保护与绑定触发
  - `platform`：Spigot / Paper / Folia 调度适配
  - `service`：绑定、卷轴、PDC 核心逻辑
  - `storage`：异步 YAML 绑定记录

## License

未指定许可证。

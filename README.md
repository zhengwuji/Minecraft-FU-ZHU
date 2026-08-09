<h1 align="center">
  <img src="src/main/resources/assets/anpilotclient/anpilot.png" alt="ANPilotClient Icon" width="108" align="center" /><br>
  ANPilotClient (Minecraft 1.20.1 Forge 版)
</h1>

<p align="center">
  <b>强大、全面、流畅且全中文界面的 Minecraft 1.20.1 Forge 客户端辅助模组</b>
</p>

---

## 📌 项目简介

**ANPilotClient** 是一款基于 **Minecraft 1.20.1 Forge (Mojang 映射)** 架构精心打造的高能 Utility 模组。集合了战斗辅助、移动增强、玩家工具、全透视觉渲染、HUD 编辑器以及智能 AI 自动建造等 **50+ 项功能模块**。

本项目界面全面支持原生中文显示、拥有自适应分辨率矢量 GUI、高清阴影点阵字体与全新高亮高对比度 UI 主题。

---

## ⚡ 快速使用教程

### 1. 安装方法
1. 确保您的 Minecraft 游戏客户端已安装 **Forge 1.20.1** (推荐 Forge 47.1.3 及以上版本)。
2. 从构建输出或 Release 中获取模组文件：[ANPilotClient-1.2.8-forge.jar](file:///g:/Personal/Desktop/test/%E6%88%91%E7%9A%84%E4%B8%96%E7%95%8C1.20.1%E8%BE%85%E5%8A%A9/ANPilotClient-1.2.8-forge.jar)。
3. 将 `.jar` 文件直接放入您客户端根目录下的 `.minecraft/mods` 文件夹中。
4. 启动游戏即可成功加载！

### 2. 界面呼出与快捷键
* **呼出主界面 (ClickGUI)**：进入游戏后，按下键盘上的 **`F4`** 键即可打开/关闭全中文辅助管理界面。
* **修改绑键**：在主界面中**中键点击**任意功能模块，按下键盘目标按键即可自定义绑定快捷键。
* **折叠/展开模块设置**：在主界面中**右键点击**模块按钮，即可展开该模块的具体参数调节面板（如数值滑块、颜色选择器、模式切换等）。
* **悬浮功能说明**：将鼠标放置于任意模块上方，界面最顶层会自动弹出清晰遮罩的**功能说明提示框**。

---

## 🔥 核心功能清单 (50+ 实用模块)

| 功能分类 | 包含模块与作用说明 |
| :--- | :--- |
| **⚔️ 战斗 (Combat)** | **自动水晶 (AutoCrystal)**、**杀手光环 (KillAura)**、**重生锚光环 (AnchorAura)**、**自动图腾 (AutoTotem)**、**更多击退 (Knockback)**、**弓箭预判 (BowAimbot)**、**自动挂床 (AutoBed)**、**脚部困人 (FeetPlace)**、**自动经验瓶 (AutoXP)**、**图腾计数 (PopCount)** 等 |
| **🏃 移动 (Movement)** | **反蜘蛛网 (AntiWeb)**、**界面移动 (GuiMove)**、**反击退 (Velocity)**、**防减速 (NoSlow)**、**发包飞行 (PacketFly)**、**速度爆发 (Boost)**、**自动跑酷 (Parkour)**、**自动跟随 (AutoFollow)**、**穿墙 (Phase)**、**安全行走 (SafeWalk)** 等 |
| **🛠️ 玩家与工具 (Player & Tool)** | **死亡幽灵 (DeathGhost)**、**自动进食 (AutoEat)**、**自动工具 (AutoTool)**、**自动装备 (AutoArmour)**、**快速挖矿 (PacketMine)**、**自动搭路 (ScaffoldPlus)**、**空中放置 (AirPlace)**、**自动附魔 (AutoEnchant)**、**自动清理背包 (AutoDrop)**、**自动抢箱子 (LootStealer)** 等 |
| **👁️ 渲染 (Render)** | **方块透视 (BlockESP)**、**实体透视 (ESP)**、**容器透视 (StorageESP)**、**名称标签 (NameTags)**、**灵魂出窍 (Freecam)**、**自由观察 (FreeLook)**、**矿物透视 (XRay)**、**登出点标记 (LogOutPoints)**、**下界隧道ESP (TunnelESP)**、**史莱姆区块 (SlimeChunks)** 等 |
| **🤖 自动化与AI (Bot & AI)** | **自动建造 (AutoBuild)**、**自动农场 (AutoFarm)**、**自动钓鱼 (AutoFish)**、**聊天工具 (ChatUtils)**、**基地扫描 (BaseFinder)**、**假人生成 (FakePlayer)**、**自动防挂机 (AntiAFK)**、**自动Log出服 (AutoLog)** 等 |
| **⚙️ 客户端设置 (ANPilot)** | **界面编辑器 (ClickGUI)**、**HUD 编辑器 (HudEditor)**、**主题管理 (PilotTheme)**、**配置保存/加载 (PilotConfig)**、**好友系统 (PilotFriend)** 等 |

---

## 💻 源码编译指南

环境要求：
* **JDK 17** 或更高版本
* **Gradle 8.0+** (建议使用项目自带的 `gradlew` 脚本)

### 编译步骤

在项目根目录下打开终端，执行以下构建命令：

**Windows (PowerShell)**:
```powershell
$env:JAVA_HOME='您的JDK17路径'
.\gradlew.bat build
```

**Linux / macOS (Bash)**:
```bash
export JAVA_HOME=/path/to/jdk-17
./gradlew build
```

编译完成后，打包好的文件位于：
`build/libs/anpilotclient-1.2.8.jar`

---

## 📜 许可证

本项目依据 **GNU General Public License v3.0 (GPL-3.0)** 开源，详见项目 `LICENSE` 文件。

---

## ⚠️ 免责声明

* 本项目仅供个人学习、编程技术研究与客户端开发实验使用。
* 请勿在禁止辅助模组的公共服务器中使用。如因违规使用导致封号等后果，由使用者自行承担。

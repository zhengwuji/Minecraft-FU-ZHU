package anpilot.forge.module;

import java.util.ArrayList;
import java.util.List;

public class ModuleManager {
    public static final List<Module> modules = new ArrayList<>();

    public static void init() {
        modules.clear();
        modules.add(new Module("KillAura", "Combat", true));
        modules.add(new Module("AutoTotem", "Combat", true));
        modules.add(new Module("ESP", "Render", true));
        modules.add(new Module("XRay", "Render", false));
        modules.add(new Module("Fly", "Movement", false));
        modules.add(new Module("Speed", "Movement", false));
        modules.add(new Module("Scaffold", "Player", false));
        modules.add(new Module("AutoEat", "Player", true));
    }
}

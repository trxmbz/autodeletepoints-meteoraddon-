package ru.example.points;

import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.example.points.modules.PointsModule;

public class PointsAddon extends MeteorAddon {
    public static final Logger LOG = LoggerFactory.getLogger("PointsAddon");
    public static final Category CATEGORY = new Category("Points");

    @Override
    public void onInitialize() {
        LOG.info("Points addon loaded");
        Modules.get().add(new PointsModule());
    }

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(CATEGORY);
    }

    @Override
    public String getPackage() {
        return "ru.example.points";
    }
}

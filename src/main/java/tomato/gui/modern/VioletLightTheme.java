package tomato.gui.modern;

import com.formdev.flatlaf.FlatLightLaf;
import java.util.Properties;
import javax.swing.UIDefaults;

/** Violet Light: the same roles, metrics and contrast rules as Violet Dark on light surfaces. */
public final class VioletLightTheme extends FlatLightLaf {
    @Override public String getName() { return "RealmShark Violet Light"; }

    @Override protected Properties getAdditionalDefaults() {
        return VioletTheme.accentDefaults(super.getAdditionalDefaults(), VioletTheme.Palette.LIGHT);
    }

    @Override public UIDefaults getDefaults() {
        UIDefaults d = super.getDefaults();
        VioletTheme.apply(d, VioletTheme.Palette.LIGHT);
        return d;
    }
}

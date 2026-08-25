package javaos.ui;

import java.awt.Font;

import javax.swing.UIManager;
import javax.swing.plaf.ColorUIResource;
import javax.swing.plaf.FontUIResource;
import javax.swing.plaf.metal.DefaultMetalTheme;
import javax.swing.plaf.metal.MetalLookAndFeel;

/**
 * The 2001-era Sun palette: Metal's "Steel" purple-grey, plus the two alternate
 * flavours that shipped alongside it. Everything is bold Dialog, the way the
 * JDK 1.3/1.4 desktop and StarOffice actually looked before Swing went quiet.
 */
public final class SunTheme extends DefaultMetalTheme {

    public enum Flavor {
        STEEL("Steel", 0x666699, 0x9999CC, 0xCCCCFF, 0x666666, 0x999999, 0xCCCCCC),
        EMERALD("Emerald", 0x417D41, 0x84B084, 0xC0DCC0, 0x666666, 0x999999, 0xCBCFCB),
        OCHRE("Ochre", 0x846B42, 0xC0A878, 0xE6DBBC, 0x6B6B5F, 0x9E9E90, 0xD1D1C4),
        SLATE("Slate", 0x51687E, 0x8FA3B8, 0xC6D3E0, 0x63676B, 0x969A9E, 0xC9CDD1);

        public final String label;
        final int p1, p2, p3, s1, s2, s3;

        Flavor(String label, int p1, int p2, int p3, int s1, int s2, int s3) {
            this.label = label;
            this.p1 = p1; this.p2 = p2; this.p3 = p3;
            this.s1 = s1; this.s2 = s2; this.s3 = s3;
        }

        /** The Metal roles, exposed so previews can paint a flavour without installing it. */
        public java.awt.Color primaryDark() { return new java.awt.Color(p1); }

        public java.awt.Color primary() { return new java.awt.Color(p2); }

        public java.awt.Color primaryLight() { return new java.awt.Color(p3); }

        public java.awt.Color control() { return new java.awt.Color(s3); }

        public java.awt.Color controlShadow() { return new java.awt.Color(s2); }

        public static Flavor byName(String name) {
            for (Flavor f : values()) {
                if (f.name().equalsIgnoreCase(name)) return f;
            }
            return STEEL;
        }
    }

    private static final FontUIResource CONTROL = new FontUIResource("Dialog", Font.BOLD, 12);
    private static final FontUIResource MENU = new FontUIResource("Dialog", Font.BOLD, 12);
    private static final FontUIResource TITLE = new FontUIResource("Dialog", Font.BOLD, 12);
    private static final FontUIResource USER = new FontUIResource("Dialog", Font.PLAIN, 12);
    private static final FontUIResource SMALL = new FontUIResource("Dialog", Font.PLAIN, 10);

    private final Flavor flavor;
    private final ColorUIResource p1, p2, p3, s1, s2, s3;

    public SunTheme(Flavor flavor) {
        this.flavor = flavor;
        this.p1 = new ColorUIResource(flavor.p1);
        this.p2 = new ColorUIResource(flavor.p2);
        this.p3 = new ColorUIResource(flavor.p3);
        this.s1 = new ColorUIResource(flavor.s1);
        this.s2 = new ColorUIResource(flavor.s2);
        this.s3 = new ColorUIResource(flavor.s3);
    }

    public Flavor flavor() {
        return flavor;
    }

    @Override public String getName() { return "Sun " + flavor.label; }

    @Override protected ColorUIResource getPrimary1() { return p1; }
    @Override protected ColorUIResource getPrimary2() { return p2; }
    @Override protected ColorUIResource getPrimary3() { return p3; }
    @Override protected ColorUIResource getSecondary1() { return s1; }
    @Override protected ColorUIResource getSecondary2() { return s2; }
    @Override protected ColorUIResource getSecondary3() { return s3; }

    @Override public FontUIResource getControlTextFont() { return CONTROL; }
    @Override public FontUIResource getSystemTextFont() { return CONTROL; }
    @Override public FontUIResource getUserTextFont() { return USER; }
    @Override public FontUIResource getMenuTextFont() { return MENU; }
    @Override public FontUIResource getWindowTitleFont() { return TITLE; }
    @Override public FontUIResource getSubTextFont() { return SMALL; }

    /** Installs the look and feel and the handful of defaults Metal gets wrong for us. */
    public static void install(Flavor flavor) {
        try {
            System.setProperty("swing.boldMetal", "true");
            MetalLookAndFeel.setCurrentTheme(new SunTheme(flavor));
            UIManager.setLookAndFeel(new MetalLookAndFeel());
        } catch (Exception e) {
            throw new IllegalStateException("Metal is part of the JDK; this should not happen", e);
        }
        UIManager.put("InternalFrame.useTaskBar", Boolean.FALSE);
        UIManager.put("ToolTip.font", new FontUIResource("Dialog", Font.PLAIN, 11));
        UIManager.put("Tree.line", "Angled");
        UIManager.put("Table.gridColor", MetalLookAndFeel.getControlShadow());
        UIManager.put("SplitPane.dividerSize", 6);
        UIManager.put("ScrollBar.width", 16);
        UIManager.put("TextArea.font", new FontUIResource("Monospaced", Font.PLAIN, 12));
    }
}

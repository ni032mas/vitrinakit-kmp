# androidx.startup discovers and instantiates this initializer by class name from a manifest
# meta-data entry. R8 cannot see that reference, so keep the class and its no-argument
# constructor explicitly rather than relying only on androidx.startup's own consumer rules.
-keep class ru.vitrina.sdk.installation.VitrinaKitInstallationIdInitializer {
    public <init>();
}

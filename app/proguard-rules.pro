# Project-specific R8 rules. Libraries ship their own consumer rules.

# Navigation reads an enum route argument by its class's name (Class.forName) and its value by the
# constant's name, so the app's enums keep both. Shrinking still drops the ones nothing uses.
-keepnames enum lab.dawnforge.hermes.** { *; }

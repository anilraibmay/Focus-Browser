# 1. SEGURANÇA E CRIPTOGRAFIA (AndroidKeyStore)
# Impede a remoção ou alteração de métodos associados à segurança nativa por hardware
-keepclassmembers class * {
    @android.security.keystore.* <fields>;
    @android.security.keystore.* <methods>;
}
-keep class javax.crypto.** { *; }
-keep class java.security.** { *; }

# 2. BLINDAGEM DO ROOM DATABASE
# Garante que as entidades de dados e DAOs mantêm as assinaturas para que o SQLite funcione
-keep class com.example.database.** { *; }
-keepclassmembers class * extends androidx.room.RoomDatabase {
    <init>(...);
}
-dontwarn androidx.room.**

# 3. INTERFACES JAVASCRIPT (Video Extractor & Pontes Web)
# CRÍTICO: Mantém os métodos anotados com @JavascriptInterface intactos.
# Se o R8 ofuscar estes métodos, o JavaScript das páginas não conseguirá comunicar com o Kotlin!
-keepattributes JavascriptInterface
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# 4. REMOÇÃO DE LOGS DE DEPURAÇÃO EM PRODUÇÃO
# Remove todas as chamadas de Log.d e Log.v para evitar fugas de metadados na consola do sistema
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
}

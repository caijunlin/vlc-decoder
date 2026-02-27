# 保持包名
-keeppackagenames com.caijunlin.**

# 保护对外暴露的 API，确保主工程能正常调用
-keep public class com.caijunlin.vlcdecoder.**.VlcStreamManager {
    public <methods>;
}

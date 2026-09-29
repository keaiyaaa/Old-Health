# 一期不开混淆（isMinifyEnabled = false）。
# 后续开启时注意：Compose 与 Navigation 的 keep 规则通常由 AGP 自带 consumer rules 覆盖，
# 但数据模型（data/model）若走反射序列化需显式 keep。
-keepattributes SourceFile,LineNumberTable

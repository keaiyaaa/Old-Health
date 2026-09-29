package com.cognidiary.app

import android.app.Application
import com.cognidiary.app.data.ServiceLocator

/**
 * Application 入口。
 *
 * 只做一件事：装配依赖。不在这里做任何 IO、不做上报、不初始化第三方 SDK ——
 * 这个应用处理的是敏感个人信息，启动路径越干净越好。
 */
class CognitiveDiaryApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
    }
}

package com.cognidiary.app.ui.copy

import com.cognidiary.app.data.model.StatusCode

/*
 * 文案表 —— docs/frontend/原型设计说明书.md 附录 A 的代码落地。
 *
 * ★ 这是全工程【唯一】的用户可见文案来源。
 *   界面里不允许出现硬编码字符串，一律从这里取。
 *   这样「禁用词校验」只需要扫这一个文件 + res/values/strings.xml。
 *
 * 禁用词（附录 A.6）：诊断、确诊、患病、概率、筛查、异常、恶化、衰退、痴呆、
 * 阿尔茨海默、病变、评分、得分、不合格。
 * MMSE / MoCA 仅研究模式可见。
 *
 * 书写约定：中文里的引号一律用全角 “ ”，不要用 ASCII 的 "，否则会截断 Kotlin 字符串。
 */

object Copy {

    // ==================== 通用 ====================

    const val EMPTY_STATE = "还没有内容"
    const val LOADING = "正在加载…"
    const val SAVE = "保存"
    const val DELETE = "删除"
    const val DIALOG_CANCEL = "先不了"
    const val DIALOG_OK = "知道了"
    const val NOT_ENOUGH_DATA = "数据不足"
    const val DISCLAIMER_BAR = "本工具只做记录和变化提示，不提供诊断。"

    val DISCLAIMER_EXPANDED = """
        本工具帮助你记录家人的语音，并观察说话方式的变化。
        它不提供诊断，也不能代替医生。所有提示都只是“和平时相比有变化”，不代表存在任何健康问题。
        如果你有担心，请带家人去专业机构做一次评估。
    """.trimIndent()

    // ==================== P-C1 启动页 ====================

    object Start {
        const val TITLE = "认知健康日记"
        const val SUBTITLE = "给家里人留个声音记录"
        const val ELDER_BUTTON = "我是老人，开始录音"
        const val FAMILY_BUTTON = "我是家属，查看记录"
    }

    // ==================== 登录（一期本地演示） ====================

    object Login {
        const val ELDER_TITLE = "登录后开始使用"
        const val FAMILY_TITLE = "登录家属端"
        const val ACCOUNT_LABEL = "账号名称或邮箱"
        const val PASSWORD_LABEL = "密码"
        const val LOGIN_BUTTON = "登录"
        const val REGISTER_BUTTON = "注册"
        const val FORGOT_BUTTON = "忘记密码"
        const val WRONG = "账号或密码不对，再试一次"
        const val REGISTER_OK = "注册好了，直接进去吧"
        const val REGISTER_SHORT = "密码至少 8 位"
        const val TEST_HINT = "测试账号：aaa　密码：123456"

        // 注册页（原型：账号名称 / 邮箱 / 密码 / 邮箱验证码 / 注册）
        const val REGISTER_TITLE = "注册账号"
        const val USERNAME_LABEL = "账号名称"
        const val EMAIL_LABEL = "邮箱"
        const val EMAIL_CODE_LABEL = "邮箱验证码"
        const val SEND_CODE_BUTTON = "发送验证码"
        const val CODE_SENT = "验证码已发送，请查收邮箱"
        const val REGISTER_SUBMIT = "注册"
        const val GENDER_SON = "我是儿子"
        const val GENDER_DAUGHTER = "我是女儿"

        // 修改密码页（原型：邮箱 / 密码 / 邮箱验证码 / 修改）
        const val FORGOT_TITLE = "修改密码"
        const val NEW_PASSWORD_LABEL = "密码"
        const val FORGOT_SUBMIT = "修改"
        const val FORGOT_OK = "密码已修改，用新密码登录吧"

        /** 风险提醒：每次登录（尤其退出后重新登录）必须展示（负责人 2026-09-28 指示） */
        const val RISK_REMINDER =
            "在继续之前，请再次确认：本工具只做声音记录和变化提示，不提供诊断，也不能代替医生。" +
                "所有提示都只是“和平时相比有变化”，不代表存在任何健康问题。" +
                "如果你有担心，请带家人去专业机构做一次评估。"
    }

    // ==================== P-O1 产品说明与免责 ====================

    object Intro {
        const val TITLE = "这个 App 能做什么"
        const val CAN_1 = "每天录一段话，留个声音记录"
        const val CAN_2 = "看看说话方式有没有变化"
        const val CAN_3 = "需要时，生成一份材料给医生"
        const val CANNOT_TITLE = "这个 App 不能做什么"
        const val CANNOT_1 = "不能诊断任何疾病"
        const val CANNOT_2 = "不能代替医生"
        const val CANNOT_3 = "不能告诉你“有没有问题”"
        const val CHECKBOX = "我已阅读并理解以上说明"
        const val NEXT = "下一步"
    }

    // ==================== P-O2 知情同意 ====================

    object Consent {
        const val TITLE = "知情同意"
        const val ITEM_1_TITLE = "录音会被上传到云端加密保存"
        const val ITEM_1_DETAIL = "保存期限 24 个月，可以随时调整或提前删除。"
        const val ITEM_2_TITLE = "谁可以看到这些记录"
        const val ITEM_2_DETAIL = "只有你邀请的家人，以及你主动分享的医生。"
        const val ITEM_3_TITLE = "你可以随时删除或注销"
        const val ITEM_3_DETAIL = "删除后不可恢复，云端音频也会一起删除。"
        const val READ_ALOUD = "语音朗读"
        const val LARGE_TEXT = "大字版查看"
        const val CHECK_AGREEMENT = "我已阅读《用户协议》《隐私政策》"
        const val CHECK_SENSITIVE = "我同意将语音等敏感个人信息用于上述用途"
        const val CONFIRM = "同意并继续"
        const val GUARDIAN_PATH = "老人无法自行决定时，由监护人代为授权"
        const val SENSITIVE_REFUSAL_NOTE =
            "语音属于敏感个人信息。不同意的话，录音功能没法使用，你可以先看看产品介绍，或咨询我们后再决定。"
    }

    // ==================== P-E 老人端 ====================

    object Elder {
        const val GREETING_SUFFIX = "，今天过得怎么样？"
        const val RECORD_HINT = "说 1 分钟就好"
        const val RECORDING_HINT = "正在听您说…"
        const val START_SPEAK = "开始说话"
        const val FINISH_SPEAK = "说完了"
        const val MIC_NEEDED = "需要开一下麦克风，点这里"
        const val OFFLINE_SAVED = "已经存好了，等联网会自己传上去。"
        const val TIMEOUT_END = "可以了，谢谢您。"
        const val RETRY_LATER = "等一下再试试"
        const val STOP_PLAYBACK = "停一下"
        const val CALENDAR_PREV_MONTH = "上个月"
        const val CALENDAR_NEXT_MONTH = "下个月"

        /** P-E4 设置页文案 */
        object Settings {
            const val BACK = "返回"
            const val VOICE_HINT_DESC = "用说的提醒我"
            const val CONSENT_TITLE = "我的同意"
            const val CONSENT_BODY = "您同意过：把说的话存起来给家人听。"
            const val CONSENT_WITHDRAW = "不同意了，停下来"
            const val WITHDRAW_TITLE = "停下来"
            const val WITHDRAW_BODY = "停下来以后，就不能录新的了。以前录的还在。想好了吗？"
            const val WITHDRAW_CONFIRM = "想好了"
            const val SWITCH_BODY = "看家里人的记录需要验证密码，点「去」后输入账号密码。"
            const val SWITCH_CONFIRM = "去"
            const val LOGOUT_BODY = "退出以后要重新选「我是老人」才能录音。"
            const val DIALOG_CANCEL = "先不了"
        }
        const val LAST_RECORD_PREFIX = "上次："
        const val UPLOADED = "已上传"
        const val WAITING_UPLOAD = "等联网上传"
        const val REPLAY = "回放"
        const val RERECORD = "重录"
        const val DONE = "完成"
        const val CALENDAR_TITLE = "我的记录"
        const val CALENDAR_EMPTY = "还没有记录呢，点一下大按钮就能录。"
        const val SETTINGS_TITLE = "设置"
        const val FONT_SIZE = "字体大小"
        const val VOICE_HINT = "语音提示"
        const val FAMILY_BINDING = "家人绑定"
        const val SWITCH_TO_FAMILY = "切换到家属模式"
        const val LOGOUT = "退出登录"

        /** 完成页正向反馈文案库。随机取，且【禁止连续两次相同】。 */
        val DONE_MESSAGES = listOf(
            "今天记好了，谢谢您。",
            "今天的声音很清楚，真好。",
            "已经存好了，您歇着吧。",
            "记下来了，明天再聊。",
            "这段留着呢，家里人能听到。",
        )
    }

    /** 每日话题卡（预置 30 条） */
    val TOPIC_CARDS: List<String> = listOf(
        // 日常
        "今天有没有什么开心的事？",
        "今天出门了吗？",
        "今天吃了什么好吃的？",
        "今天天气怎么样？",
        "最近在看什么电视？",
        "今天早上几点起来的？",
        "家里的花花草草长得怎么样？",
        "今天有没有出去走走？",
        "今天和谁说过话？",
        "今天有没有做什么家务？",
        // 回忆
        "说说你年轻时候的事",
        "小时候家里是什么样子的？",
        "你最拿手的一道菜是怎么做的？",
        "你小时候上学是怎么去的？",
        "你第一份工作是什么？",
        "你和老伴是怎么认识的？",
        "你印象最深的一次旅行是去哪？",
        "小时候过年都做些什么？",
        "你最要好的老朋友是谁？",
        "你以前住的地方是什么样子？",
        // 情绪
        "最近有什么事让你觉得高兴？",
        "有没有什么事让你觉得烦心？",
        "最近睡得怎么样？",
        "最近胃口好吗？",
        "有没有什么想做还没做的事？",
        // 关系
        "最近和谁联系过？",
        "老朋友里谁最常来往？",
        "孩子们最近有消息吗？",
        "家里最近有什么新鲜事？",
        "你最喜欢和谁聊天？",
    )

    // ==================== P-F 家属端 ====================

    object Family {
        const val TITLE = "认知健康日记"
        const val RESEARCH_MODE = "研究模式"
        const val TREND_DETAIL = "看看详细趋势"
        const val VISIT_PREP = "准备就诊材料"
        const val SYNC_NORMAL = "最近同步：%s"
        const val SYNC_LAGGING = "最近同步：%s，数据可能滞后"
        const val SYNC_FAILED = "有 %d 条还没传上去，正在重试"
        const val RECORD_COUNT_FORMAT = "本周 %d/7 天有记录"
        const val REMIND_CARD_TITLE = "今天%s还没有记录"
        const val REMIND_CARD_BODY = "可以提醒他一下，或者你帮他录一段。"
        const val REMIND_CARD_ACTION = "帮他录"
        const val QUICK_TOPICS = "话题库"
        const val QUICK_VISIT = "就医准备"
        const val QUICK_INVITE = "邀请家人"

        // P-F1 总览（对齐 cognitive-health-diary/family-overview.html）
        const val OVERVIEW_TITLE = "总览"
        const val HEADER_DATE = "今天 · %s"
        const val STREAK_FORMAT = "已连续记录 %d 天 · 最近一次 %s"
        const val METRICS_TITLE = "说话的四个方面"
        const val METRICS_NOTE = "都只跟她自己以前比，不跟别人比，也不给分数"
        const val LINK_TRENDS = "趋势详情"
        const val DATA_OK = "这次分析用了 %d 条有效记录 · 数据够用"
        const val DATA_SHORT = "有效记录 %d 条 · 还不够看趋势（需要至少 4 条）"
        const val DIR_LOWER = "比平时低一点"
        const val DIR_SAME = "跟平时差不多"
        const val DIR_HIGHER = "比平时高一点"
        const val METRIC_INSUFFICIENT = "记录不够，暂时看不出来"
        const val TAG_S2 = "稳定"
        const val TAG_S3 = "先不下结论"
        const val TAG_S4 = "留意一下"
        const val TAG_S5 = "建议就医评估"
        const val TAG_S6 = "已就医"
        const val TAG_DEFAULT = "进行中"
        const val REMIND_TITLE = "今天还没有录音"
        const val REMIND_BODY = "按平时习惯，晚上 %s 会录一段。到点会提醒一次，连着没录也不会一直催。"
        const val REMIND_BUTTON = "提醒她录一段"
        const val REMIND_DEMO = "已记下提醒（一期演示：通知将在接入系统通知后生效）"
        const val NEXT_TITLE = "接下来可以做什么"
        const val DAILY_TOPICS = "每日话题"
        const val INVITE = "邀请家人"
        const val INVITE_SHEET_TITLE = "邀请家人一起看"
        const val INVITE_BODY = "被邀请的家人才看得到记录。一期为演示，正式邀请功能待服务端接入后生效。"
        const val INVITE_CODE_LABEL = "邀请码（演示）"

        // 添加老人（真机联调：创建老人并生成一次性绑定码，API-01 1.9）
        const val ADD_ELDER_TITLE = "添加老人"
        const val ADD_ELDER_NAME_LABEL = "老人的称呼（如：爸爸）"
        const val ADD_ELDER_BAND_LABEL = "年龄段"
        const val ADD_ELDER_SUBMIT = "创建并生成绑定码"
        const val ADD_ELDER_CODE_TITLE = "绑定码（24 小时内有效，只显示一次）"
        const val ADD_ELDER_CODE_BODY = "请让老人在老人端登录后，输入称呼和这个绑定码完成绑定。绑定后，老人的记录会出现在这里。"
        const val ADD_ELDER_EMPTY_NAME = "请先填写老人的称呼"
        const val BIND_TITLE = "绑定老人"
        const val BIND_NAME_LABEL = "老人的称呼"
        const val BIND_CODE_LABEL = "家人告诉你的绑定码"
        const val BIND_SUBMIT = "绑定"
        const val BIND_HINT = "绑定码由家属端「添加老人」生成。绑定后才能在这里记录和查看。"

        // P-F2 趋势
        const val SUFFICIENCY_FORMAT = "本周仅 %d 次记录，趋势仅供参考"
        const val RANGE_4W = "近 4 周"
        const val RANGE_3M = "3 个月"
        const val RANGE_ALL = "全部"
        const val EXPLAIN_TITLE = "这意味着什么"
        const val EXPLAIN_SPEECH_RATE =
            "平时 1 分钟大约说 180 个字，这周平均 150 个。说得慢一些，可能是累了，也可能是最近话变少了。"
        const val EXPLAIN_FLUENCY =
            "说话中间停下来的次数比平时多了一些。感冒鼻塞、环境吵、或者说到不熟的话题都会有影响。"
        const val EXPLAIN_LEXICAL = "这周用的不一样的词，比平时少了一点。"
        const val EXPLAIN_COHERENCE = "句子的连接比平时松了一些，说到后面容易换话题。"
        const val TRANSCRIPT_MISSING = "本周文本分析未完成"
        const val EVENT_LABEL = "事件标注"
        const val EVENT_ADD = "+ 添加"

        // P-F3 / P-F4
        const val RECORDS_TITLE = "全部记录"
        const val RECORDS_FILTER = "筛选"
        const val RECORDS_EMPTY = "还没有记录。等他录第一条之后，这里就能看到了。"
        const val DETAIL_METRIC_TITLE = "今天的指标（相对他自己的基线）"
        const val DETAIL_DIRECTION_LOWER = "↓ 比平时慢一些"
        const val DETAIL_DIRECTION_SAME = "→ 和平时差不多"
        const val DETAIL_TRANSCRIPT = "他说了什么"
        const val DETAIL_EXPAND = "展开"
        const val DETAIL_CORRECT = "修正"
        const val DETAIL_NOTE_TITLE = "我想补充点什么"
        const val DETAIL_NOTE_HINT = "这周感冒了，嗓子不舒服"
        const val DETAIL_DELETE = "删除这条记录"
        const val DETAIL_DELETE_CONFIRM = "删除后不可恢复，云端音频也会一起删掉。确定删除吗？"

        // P-F5
        const val TOPICS_TITLE = "话题库与提醒"
        const val TOPICS_DAILY = "每日话题"
        const val TOPICS_ADD = "+ 添加话题"
        const val TOPICS_EDIT = "编辑"
        const val REMINDER_TITLE = "提醒"
        const val REMINDER_ELDER = "提醒老人"
        const val REMINDER_ME = "提醒我"
        const val REMINDER_SWITCH = "开"
        const val REMINDER_FREQ_NOTE =
            "如果连续 3 天没有记录，提醒会自动改成隔天一次，避免给他压力。"

        // P-F6
        const val VISIT_TITLE = "就医准备"
        const val VISIT_PREVIEW = "就诊摘要预览"
        const val VISIT_EXPORT_PDF = "导出 PDF"
        const val VISIT_EXPORT_IMAGE = "导出长图"
        const val VISIT_SHARE = "分享"
        const val VISIT_SUMMARY_TITLE_FORMAT = "%s的就诊摘要"
        const val SUMMARY_SUBJECT = "被记录人：%s（%s，%d 岁）"
        const val SUMMARY_RANGE = "记录范围：%s 起"
        const val SUMMARY_COUNT = "记录条数：%d 条"
        const val SUMMARY_DURATION = "总时长：%s"
        const val SUMMARY_STATUS = "当前状态：%s"
        const val VISIT_INSUFFICIENT = "记录还太少（需要至少 4 条），暂时生成不了就诊摘要。"
        const val VISIT_CHECKLIST_TITLE = "去医院前，你可以准备："
        const val VISIT_CHECKLIST_DEPT = "挂什么科：神经内科 / 记忆门诊 / 老年科"
        const val VISIT_CHECKLIST_BRING =
            "带什么：身份证、医保卡、既往病历、正在吃的药（含药盒照片）"
        const val VISIT_CHECKLIST_QUESTIONS = "可以问医生这 5 个问题 →"

        /** 就诊摘要里「想问医生的问题」模板（内容需医生审阅后再定稿） */
        val VISIT_QUESTIONS = listOf(
            "他近期的表达变化，需要做进一步评估吗？",
            "如果需要评估，建议做哪些项目？",
            "这些变化可能和哪些因素有关？",
            "日常生活上我们可以做些什么？",
            "下次复查大概隔多久合适？",
        )

        // P-F7
        const val PRIVACY_TITLE = "数据与隐私"
        const val PRIVACY_SYNC_TITLE = "同步状态"
        const val PRIVACY_SYNC_NOW = "立即同步"
        const val PRIVACY_PENDING_FORMAT = "待上传：%d 条"
        const val PRIVACY_CACHE_FORMAT = "本地缓存：%s"
        const val PRIVACY_CONSENT_TITLE = "同意状态"
        const val PRIVACY_CONSENT_ITEMS = "用户协议 · 隐私政策 · 敏感信息授权"
        const val PRIVACY_VIEW = "查看"
        const val PRIVACY_WITHDRAW = "撤回"
        const val PRIVACY_RETENTION_TITLE = "音频保存期限"
        const val PRIVACY_RETENTION_VALUE = "云端保留 24 个月"
        const val PRIVACY_ADJUST = "调整"
        const val PRIVACY_MY_DATA = "我的数据"
        const val PRIVACY_EXPORT_ALL = "导出全部数据（音频 + 指标 + 备注）"
        const val PRIVACY_CLEAR_ALL = "清空全部记录"
        const val PRIVACY_DELETE_ACCOUNT = "注销账号并删除全部数据"
        const val PRIVACY_PERMISSION_TITLE = "权限说明"
        const val PRIVACY_PERMISSION_NOTE = "本应用不申请通讯录、定位、相册权限。"
        const val PRIVACY_WITHDRAW_CONFIRM =
            "撤回后我们会停止上传新的记录。已有的记录要一起删除吗？"
        const val PRIVACY_DELETE_CONFIRM =
            "注销会彻底删除全部记录和音频，不可恢复。请输入“删除”确认。"

        // P-F9 家属端设置
        const val SETTINGS_TITLE = "设置"
        const val SWITCH_TO_ELDER = "切换到老人端"
        const val SWITCH_BODY = "要回到老人端的录音页吗？回来的时候在设置里再切一次就好。"

        // P-F10 家属端查看密码
        const val PIN_TITLE = "输入查看密码"
        const val PIN_LABEL = "查看密码"
        const val PIN_WRONG = "密码不对，再试一次"
        const val PIN_HINT = "查看密码由家属自行设置，用于进入家属端，防止其他人查看。测试密码：000000"
        const val PIN_CHANGE_TITLE = "修改查看密码"
        const val PIN_CHANGE_BODY = "查看密码用于进入家属端，与账号密码相互独立。至少 6 位。"
        const val PIN_NEW = "新密码"
        const val PIN_CONFIRM = "再输一次"
        const val PIN_MISMATCH = "两次输入不一致"
        const val PIN_SAVED = "查看密码已更新"

        // P-F8
        const val RESEARCH_TITLE = "研究模式"
        const val RESEARCH_CONSENT = "研究知情同意"
        const val RESEARCH_SIGNED = "已签署"
        const val RESEARCH_SUBJECT_ID = "受试者编号"
        const val RESEARCH_SCALE_TITLE = "临床量表录入"
        const val RESEARCH_SCALE_LABEL = "量表"
        const val RESEARCH_SCORE = "分数"
        const val RESEARCH_DATE = "评估日期"
        const val RESEARCH_ASSESSOR = "评估人"
        const val RESEARCH_SAVE = "保存"
        const val RESEARCH_TASK_TITLE = "结构化任务"
        const val RESEARCH_ADHERENCE = "依从性"
        const val RESEARCH_EXPORT = "导出原始数据集"
        const val RESEARCH_EXPORT_NOTE = "含音频 / ASR 文本 / 人工转录 / 指标"
        const val RESEARCH_CONSENT_NOTE =
            "本研究的数据仅用于科研，可随时退出，退出后可要求删除。"
    }

    // ==================== 状态横幅（附录 A.3）====================

    object Status {
        const val S0_TITLE = "先录第一条吧"
        const val S0_BODY = "第一次录只是给他建立一个自己的基准。基准有了，后面的变化才有意义。"

        const val S1_TITLE = "还需要 %d 次记录，才能开始看趋势"
        const val S1_BODY = "前几次记录用来建立他自己的基准。"

        const val S2_TITLE = "本周的语言表达和平时差不多"
        const val S2_BODY = "说话速度、流畅度、用词和连贯性都在他自己的正常范围内。"

        const val S3_TITLE = "这周记录少了点，趋势先不下结论"
        const val S3_BODY = "这周有效记录不足，先不下结论。多录几次再看。"

        const val S4_TITLE = "近两周的表达有一点变化，先继续观察"
        const val S4_BODY_FORMAT =
            "有 %d 项比平时低一些：%s。建议继续记录，并留意他平时的状态。"

        const val S5_TITLE = "近一个月的表达变化比较持续"
        const val S5_BODY_FORMAT =
            "有 %d 项指标持续偏低：%s。可以和家人商量，带他去做一次专业的记忆或认知评估 —— 这不代表有问题，只是做个确认更安心。"

        const val S6_TITLE = "已记录就医情况，继续记录有助于医生看长期变化"
        const val S6_BODY = "继续记录，医生能看到更长期的变化。"

        /**
         * 非疾病解释文案库。S4/S5 必填，随机取。
         *
         * 注意：这些文案的作用是把「可能是疾病」这个念头拉回来，
         * 不是免责声明。视觉比重必须 ≥1/3。
         */
        val NON_DISEASE_EXPLANATIONS = listOf(
            "也可能只是最近累了、睡得不好。",
            "感冒、鼻塞、嗓子不舒服都会有影响。",
            "如果家里环境比较吵，录出来的结果也会受影响。",
            "换了个不熟悉的话题，说得慢一点是正常的。",
            "情绪不好、心情低落的时候，说话也会变少。",
        )

        fun title(code: StatusCode, remainingCount: Int = 0): String = when (code) {
            StatusCode.S0 -> S0_TITLE
            StatusCode.S1 -> String.format(S1_TITLE, remainingCount)
            StatusCode.S2 -> S2_TITLE
            StatusCode.S3 -> S3_TITLE
            StatusCode.S4 -> S4_TITLE
            StatusCode.S5 -> S5_TITLE
            StatusCode.S6 -> S6_TITLE
        }

        fun body(code: StatusCode, changedCount: Int = 0, metricNames: String = ""): String =
            when (code) {
                StatusCode.S0 -> S0_BODY
                StatusCode.S1 -> S1_BODY
                StatusCode.S2 -> S2_BODY
                StatusCode.S3 -> S3_BODY
                StatusCode.S4 -> String.format(S4_BODY_FORMAT, changedCount, metricNames)
                StatusCode.S5 -> String.format(S5_BODY_FORMAT, changedCount, metricNames)
                StatusCode.S6 -> S6_BODY
            }
    }
}

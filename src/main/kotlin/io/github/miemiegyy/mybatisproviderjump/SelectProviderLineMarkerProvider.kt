package io.github.miemiegyy.mybatisproviderjump // 插件包名，与 plugin.xml 中注册保持一致

import com.intellij.codeInsight.daemon.LineMarkerInfo // 行标记信息对象：描述 gutter 图标出现在哪一行、锚定哪个 PSI 元素
import com.intellij.codeInsight.daemon.LineMarkerProvider // 行标记提供者接口：IDEA 定期回调它来收集 gutter 图标
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder // gutter 图标构建器：可导航图标（点击跳转到目标）的组装工具
import com.intellij.icons.AllIcons // IDEA 内置图标库
import com.intellij.openapi.editor.markup.GutterIconRenderer // gutter 图标渲染器基类，Alignment 定义图标在行内的对齐方式
import com.intellij.psi.PsiClass // PSI 类节点，代表 Java 源码中的一个类
import com.intellij.psi.PsiElement // PSI 元素基类，IDEA 中所有源码节点（方法、注解、标识符等）的公共父类型
import com.intellij.psi.PsiMethod // PSI 方法节点，代表 Java 源码中的一个方法

/**
 * 正向导航：Mapper 接口方法上的 @SelectProvider / @InsertProvider /
 * @UpdateProvider / @DeleteProvider 注解 -> Provider 类的 SQL 构建方法。
 *
 * 在 Mapper 方法名左侧显示 Forward 图标，点击跳转到生效的 Provider 方法
 * （`method` 显式指定、同名约定或 `provideSql` 兜底，见 [ProviderAnnotation.Info.effectiveMethodName]）。
 */
class SelectProviderLineMarkerProvider : LineMarkerProvider { // 正向导航 Provider，通过 plugin.xml 注册到 language="JAVA"

    // 快路径：返回 null 表示不在编辑器实时（EDT）阶段处理，统一放到后台的 collectSlowLineMarkers
    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? = null

    // 慢路径：在后台线程中对一批 PSI 元素收集行标记；大量/耗时的计算都应放在这里，避免卡顿编辑器
    override fun collectSlowLineMarkers(
        elements: List<PsiElement>, // 当前文件中待处理的一批 PSI 元素（IDEA 分批传入）
        result: MutableCollection<in LineMarkerInfo<*>>, // 收集到的行标记统一放进这个集合，IDEA 负责渲染
    ) {
        val resolverCache = HashMap<PsiClass, Boolean>() // Provider 类 -> 是否实现 ProviderMethodResolver（本批次内缓存，避免重复计算继承链）
        for (element in elements) { // 逐个遍历这批元素
            if (element !is PsiMethod) continue // 只处理方法节点，其他元素（类、语句等）跳过
            val identifier = element.nameIdentifier ?: continue // 取方法名标识符（如 "selectById" 这个词本身），作为图标锚点；匿名/残缺方法没有标识符则跳过

            for ((_, info) in ProviderAnnotation.findProviderAnnotations(element)) { // 遍历该方法上所有受支持的 Provider 注解及其解析结果（忽略注解节点本身，用下划线占位）
                val providerClass = info.providerClass ?: continue // 取 type 属性解析出的 Provider 类；类解析失败（如依赖缺失）则跳过
                val sameNameConvention = resolverCache.getOrPut(providerClass) { // 同名约定开关：本类是否实现 ProviderMethodResolver
                    ProviderAnnotation.implementsProviderMethodResolver(providerClass) // 含间接继承（如自定义 Provider 基类）
                }
                val methodName = info.effectiveMethodName(element, sameNameConvention) // 生效的 Provider 方法名（显式 method / 同名约定 / provideSql 兜底）

                val targets = providerClass.findMethodsByName(methodName, true) // 在 Provider 类中按名字查找方法；true 表示同时查父类继承的方法（同名重载会全部返回）
                if (targets.isEmpty()) continue // 目标类里找不到对应方法，不挂图标（避免点了没反应的空图标）

                // 同名重载精准匹配：按 Mapper 方法参数个数过滤候选；无参数个数匹配时保留全部候选（点击弹列表选择）
                val paramCount = element.parameterList.parametersCount // Mapper 方法的参数个数
                val precise = targets.filter { it.parameterList.parametersCount == paramCount } // 参数个数一致的候选
                val chosen = if (precise.isNotEmpty()) precise else targets.toList() // 精准命中用精准集；否则退回全部同名候选

                result += NavigationGutterIconBuilder // 开始组装可导航的 gutter 图标
                    .create(AllIcons.Actions.Forward) // 图标：内置的"前进"箭头（➡），表示从 Mapper 跳向 Provider
                    .setAlignment(GutterIconRenderer.Alignment.LEFT) // 对齐方式：图标紧贴行号栏左侧（行标记规范要求）
                    .setTooltipText("Navigate to provider method '$methodName' in ${providerClass.name}") // 鼠标悬停提示文案
                    .setTargets(chosen) // 设置跳转目标：唯一命中直接跳，多候选点击弹列表选择
                    .createLineMarkerInfo(identifier) // 把图标锚定到方法名标识符上，生成 LineMarkerInfo 加入结果集
            }
        }
    }
}

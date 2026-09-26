package io.github.miemiegyy.mybatisproviderjump // 插件包名，与 plugin.xml 中注册保持一致

import com.intellij.codeInsight.daemon.LineMarkerInfo // 行标记信息对象：描述 gutter 图标出现在哪一行、锚定哪个 PSI 元素
import com.intellij.codeInsight.daemon.LineMarkerProvider // 行标记提供者接口：IDEA 定期回调它来收集 gutter 图标
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder // gutter 图标构建器：可导航图标（点击跳转到目标）的组装工具
import com.intellij.icons.AllIcons // IDEA 内置图标库
import com.intellij.openapi.editor.markup.GutterIconRenderer // gutter 图标渲染器基类，Alignment 定义图标在行内的对齐方式
import com.intellij.psi.PsiAnnotation // PSI 注解节点，代表 Java 源码中的一个 @注解
import com.intellij.psi.PsiClass // PSI 类节点，代表 Java 源码中的一个类
import com.intellij.psi.PsiElement // PSI 元素基类，IDEA 中所有源码节点的公共父类型
import com.intellij.psi.PsiIdentifier // PSI 标识符节点（如方法名这个词本身），作为图标锚点
import com.intellij.psi.PsiMethod // PSI 方法节点，代表 Java 源码中的一个方法
import com.intellij.psi.search.searches.ReferencesSearch // 引用搜索：反向查找"哪里引用了这个类/元素"
import com.intellij.psi.util.PsiTreeUtil // PSI 树工具：在 PSI 树上向上/向下找特定类型的父/子节点

/**
 * 反向导航：Provider 类的 SQL 构建方法 -> 引用它的 Mapper 方法。
 *
 * 按宿主类分组收集：一个 Provider 类只做一次全项目 [ReferencesSearch]，
 * 再按"生效方法名"（显式 method / 同名约定 / provideSql 兜底）分发到
 * 本批元素中同名的方法上；存在多个引用点时点击弹出列表选择。
 */
class ProviderToMapperLineMarkerProvider : LineMarkerProvider { // 反向导航 Provider，通过 plugin.xml 注册到 language="JAVA"

    // 快路径：返回 null 表示不在编辑器实时（EDT）阶段处理，统一放到后台的 collectSlowLineMarkers
    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? = null

    // 慢路径：在后台线程中对一批 PSI 元素收集行标记；引用搜索较耗时，必须放在这里
    override fun collectSlowLineMarkers(
        elements: List<PsiElement>, // 当前文件中待处理的一批 PSI 元素（IDEA 分批传入）
        result: MutableCollection<in LineMarkerInfo<*>>, // 收集到的行标记统一放进这个集合，IDEA 负责渲染
    ) {
        // 第一步：按宿主类分组整理本批元素中的方法（关键：同一 Provider 类的所有方法共享一次引用搜索）
        val identifiersByClass = LinkedHashMap<PsiClass, MutableMap<String, MutableList<PsiIdentifier>>>() // Provider 类 -> (方法名 -> 该方法名标识符列表)
        for (element in elements) { // 逐个遍历这批元素
            if (element !is PsiMethod) continue // 只处理方法节点，其他元素跳过
            val identifier = element.nameIdentifier ?: continue // 取方法名标识符作为图标锚点；取不到则跳过
            val containingClass = element.containingClass ?: continue // 取方法所在的宿主类（Provider 类）；不在类里的方法跳过
            identifiersByClass.getOrPut(containingClass) { LinkedHashMap() } // 按类分组（保持出现顺序）
                .getOrPut(element.name) { mutableListOf() } // 按方法名再分组
                .add(identifier) // 挂上锚点
        }

        val resolverCache = HashMap<PsiClass, Boolean>() // Provider 类 -> 是否实现 ProviderMethodResolver（本批次内缓存）
        for ((providerClass, methodsByName) in identifiersByClass) { // 逐类处理
            // 同名约定开关：本类实现 ProviderMethodResolver 时，省略 method 的注解按 Mapper 方法同名解析；否则按 MyBatis 默认找 provideSql
            val sameNameConvention = resolverCache.getOrPut(providerClass) {
                ProviderAnnotation.implementsProviderMethodResolver(providerClass) // 含间接继承（如自定义 Provider 基类）
            }

            // 第二步：本类一次引用搜索，按"生效方法名"分发引用点（N 个方法只搜 1 次，而非原来的 N 次）
            val mapperMethodsByEffectiveName = HashMap<String, LinkedHashSet<PsiMethod>>() // 生效方法名 -> 引用它的 Mapper 方法（去重且保序）
            for ((mapperMethod, info) in referencingMapperMethods(providerClass)) { // 遍历引用了本类的所有 Provider 注解
                val effective = info.effectiveMethodName(mapperMethod, sameNameConvention) // 该引用点生效的 Provider 方法名（与 MyBatis 语义一致）
                mapperMethodsByEffectiveName.getOrPut(effective) { LinkedHashSet() }.add(mapperMethod) // 按生效名归类
            }

            // 第三步：本类方法按名字领取自己的 Back 图标
            for ((methodName, identifiers) in methodsByName) { // 遍历本批元素中该类的每个方法名
                val targets = mapperMethodsByEffectiveName[methodName]?.toList() ?: continue // 没有 Mapper 引用这个方法（如普通辅助方法），不挂图标
                for (identifier in identifiers) { // 同名方法可能有多个（重载），各自挂图标
                    result += NavigationGutterIconBuilder // 开始组装可导航的 gutter 图标
                        .create(AllIcons.Actions.Back) // 图标：内置的"后退"箭头（⬅），表示从 Provider 跳回 Mapper
                        .setAlignment(GutterIconRenderer.Alignment.LEFT) // 对齐方式：图标紧贴行号栏左侧（行标记规范要求）
                        .setTooltipText("Navigate to Mapper method using this provider") // 鼠标悬停提示文案
                        .setTargets(targets) // 设置跳转目标：一个 Mapper 方法直接跳，多个时点击弹列表选择
                        .createLineMarkerInfo(identifier) // 把图标锚定到方法名标识符上，生成 LineMarkerInfo 加入结果集
                }
            }
        }
    }

    /** 反查：哪些 Mapper 方法的 Provider 注解引用了 [providerClass]，返回 (Mapper 方法, 注解解析结果) 列表。 */
    private fun referencingMapperMethods(providerClass: PsiClass): List<Pair<PsiMethod, ProviderAnnotation.Info>> { // 每个 Provider 类在一次收集中只调用一次
        val targets = LinkedHashSet<Pair<PsiMethod, ProviderAnnotation.Info>>() // 用 LinkedHashSet 去重且保持顺序
        for (reference in ReferencesSearch.search(providerClass)) { // 全项目搜索"谁引用了这个 Provider 类"（@SelectProvider(type = X.class) 里的 X 也算引用）
            val refElement = reference.element // 引用点的 PSI 元素（这里就是注解里 "X.class" 的那个代码引用节点）

            // 引用点向上找所在的注解，例如 @SelectProvider(type = X.class, ...)
            val annotation = PsiTreeUtil.getParentOfType(refElement, PsiAnnotation::class.java, true) // 从引用点向上找最近的 PsiAnnotation；true 表示可以越过非严格父链
                ?: continue // 这个类引用不在任何注解里（比如 new X()、字段声明），跳过
            val info = ProviderAnnotation.resolve(annotation) ?: continue // 解析该注解；不是四个 Provider 注解之一则跳过
            if (info.providerClass != providerClass) continue // 引用的不是当前类（理论上 search 已保证，双保险），跳过

            // 向上找到承载注解的 Mapper 方法
            val mapperMethod = PsiTreeUtil.getParentOfType(annotation, PsiMethod::class.java, false) // 从注解向上找所在的 PsiMethod；false 表示找不到就算（注解也可能挂在类等别处）
                ?: continue // 注解不挂在方法上（如类级注解），跳过
            targets.add(mapperMethod to info) // 找到一对（Mapper 方法, 注解解析结果），加入结果集（Set 自动去重）
        }
        return targets.toList() // 转成 List 返回
    }
}

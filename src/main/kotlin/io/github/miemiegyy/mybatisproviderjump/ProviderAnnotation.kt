package io.github.miemiegyy.mybatisproviderjump // 插件包名，与 plugin.xml 中注册保持一致

import com.intellij.psi.JavaPsiFacade // Java PSI 入口：按全限定名在项目中查找类（这里用来定位 MyBatis 的 ProviderMethodResolver 接口）
import com.intellij.psi.PsiAnnotation // IDEA 的 PSI 注解节点，代表 Java 源码中的一个 @注解
import com.intellij.psi.PsiAnnotationMemberValue // 注解属性的值节点（type = X.class 的 "X.class" 部分）
import com.intellij.psi.PsiClass // PSI 类节点，代表 Java 源码中的一个类
import com.intellij.psi.PsiClassObjectAccessExpression // PSI 的 "Xxx.class" 类对象访问表达式节点
import com.intellij.psi.PsiClassType // PSI 类类型（ClassType），可通过 resolve() 解析出对应的 PsiClass
import com.intellij.psi.PsiLiteralExpression // PSI 字面量表达式节点，代表 "字符串"、数字等字面量
import com.intellij.psi.PsiMethod // PSI 方法节点，代表 Java 源码中的一个方法
import com.intellij.psi.search.GlobalSearchScope // PSI 搜索范围（这里用全项目范围找 mybatis jar 里的接口）

/**
 * MyBatis Provider 注解解析工具。
 *
 * 支持以下注解的 `type` / `method` 属性：
 * - [org.apache.ibatis.annotations.SelectProvider]
 * - [org.apache.ibatis.annotations.InsertProvider]
 * - [org.apache.ibatis.annotations.UpdateProvider]
 * - [org.apache.ibatis.annotations.DeleteProvider]
 *
 * `method` 省略时的生效方法名与 MyBatis `ProviderSqlSource` 语义保持一致：
 * Provider 实现 `ProviderMethodResolver` 时按 Mapper 方法同名解析（同名约定），
 * 否则回退到 MyBatis 默认的 `provideSql` 方法。
 */
object ProviderAnnotation { // Kotlin 单例对象，整个插件共享一个实例，无需 new

    // 四个受支持的 MyBatis Provider 注解的全限定名集合，用于快速判断注解类型
    private val SUPPORTED_ANNOTATIONS = setOf(
        "org.apache.ibatis.annotations.SelectProvider",   // 查询 SQL 提供者注解
        "org.apache.ibatis.annotations.InsertProvider",   // 插入 SQL 提供者注解
        "org.apache.ibatis.annotations.UpdateProvider",   // 更新 SQL 提供者注解
        "org.apache.ibatis.annotations.DeleteProvider",   // 删除 SQL 提供者注解
    )

    /** MyBatis 省略 method 且 Provider 未实现 ProviderMethodResolver 时的默认 Provider 方法名。 */
    const val DEFAULT_PROVIDER_METHOD = "provideSql" // 对应 MyBatis ProviderSqlSource 的兜底查找名

    /** 解析结果。[providerClass] 可能为 null（类无法解析），[methodName] 可能为 null（属性省略）。 */
    class Info( // 数据持有类：一个 Provider 注解解析出的关键信息
        val providerClass: PsiClass?, // type 属性指向的 Provider 类；解析失败时为 null
        val methodName: String?,      // method 属性指定的目标方法名；省略时为 null
    ) {
        /**
         * 生效的 Provider 方法名（与 MyBatis ProviderSqlSource 的解析语义一致）：
         * - `method` 显式非空 → 该值；
         * - `method` 省略/空串 且 Provider 实现 ProviderMethodResolver（[sameNameConvention]=true）
         *   → Mapper 方法同名（同名约定）；
         * - `method` 省略/空串 且未实现 → MyBatis 默认找 `provideSql`。
         */
        fun effectiveMethodName(mapperMethod: PsiMethod, sameNameConvention: Boolean): String = // 按 MyBatis 语义计算生效方法名
            methodName?.takeIf { it.isNotEmpty() } // 显式非空 method 优先
                ?: if (sameNameConvention) mapperMethod.name else DEFAULT_PROVIDER_METHOD // 同名约定 or provideSql 兜底
    }

    /**
     * 判断 Provider 类是否实现 MyBatis 的 [org.apache.ibatis.builder.annotation.ProviderMethodResolver]
     * （同名约定的开关；未实现时 MyBatis 省略 method 会去找 provideSql）。
     * 该方法含继承链计算，调用方宜在同一收集批次内按 [PsiClass] 缓存结果。
     */
    fun implementsProviderMethodResolver(providerClass: PsiClass): Boolean { // true = 同名约定生效
        val project = providerClass.project
        val resolverClass = JavaPsiFacade.getInstance(project).findClass( // 在项目中解析 MyBatis 的接口类
            "org.apache.ibatis.builder.annotation.ProviderMethodResolver", // 同名约定接口的全限定名
            GlobalSearchScope.allScope(project), // 全项目范围（mybatis jar 在模块依赖里即可找到）
        ) ?: return false // 找不到接口定义（极端情况）按未实现处理，走 provideSql 兜底
        return providerClass.isInheritor(resolverClass, true) // true = 含间接继承（如自定义 Provider 基类实现了该接口）
    }

    /** 若不是受支持的 Provider 注解，返回 null。 */
    fun resolve(annotation: PsiAnnotation): Info? { // 入口方法：把一个 PSI 注解解析成 Info
        val qualifiedName = annotation.qualifiedName ?: return null // 取注解全限定名（如 org.apache.ibatis.annotations.SelectProvider），拿不到（如注解无法解析）则放弃
        if (qualifiedName !in SUPPORTED_ANNOTATIONS) return null    // 不在支持的四个注解名单里，直接返回 null（不是我们的目标注解）

        val providerClass = extractClass(annotation) // 解析 type / value 属性（二者是别名），提取出目标 PsiClass
        val methodName = extractString(annotation.findDeclaredAttributeValue("method")) // 解析 method 属性值（method = "m"），提取出字符串 "m"
        return Info(providerClass, methodName) // 打包成 Info 返回；type/method 属性缺失时对应字段为 null
    }

    /**
     * 提取 `type = X.class` 或 `value = X.class`（bare 短写法 `@SelectProvider(X.class)`）中的 [PsiClass]。
     * 注意必须用 findDeclaredAttributeValue：MyBatis 注解的 type()/value() 默认值是 void.class，
     * 用 findAttributeValue 在未显式书写时会静默返回 void.class 的 PsiClass，导致匹配到错误的类。
     */
    private fun extractClass(annotation: PsiAnnotation): PsiClass? { // 从注解中提取 Provider 类（type 优先，value 兜底）
        val value = annotation.findDeclaredAttributeValue("type") // 显式书写的 type 属性（findDeclared 只取显式书写，不取注解默认值）
            ?: annotation.findDeclaredAttributeValue("value")       // 显式书写的 value 属性（bare 短写法 @SelectProvider(X.class) 绑定到这里）
        val accessExpression = value as? PsiClassObjectAccessExpression ?: return null // 属性值必须是 "Xxx.class" 形式的类对象访问表达式，否则返回 null
        return (accessExpression.operand.type as? PsiClassType)?.resolve() // 取表达式的操作数（类型元素）的 ClassType，resolve() 解析出对应的 PsiClass；失败返回 null
    }

    /** 提取 `method = "name"` 中的字符串字面量。 */
    private fun extractString(value: PsiAnnotationMemberValue?): String? { // 从注解属性值节点中提取字符串
        return (value as? PsiLiteralExpression)?.value as? String // 属性值必须是字符串字面量（如 "buildSql"），取其原始值并强转为 String；否则返回 null
    }

    /** 判断某个 Mapper 方法是否标注了任一受支持的 Provider 注解，返回 (注解, 解析结果) 列表。 */
    fun findProviderAnnotations(method: PsiMethod): List<Pair<PsiAnnotation, Info>> = // 遍历方法上的所有注解
        method.annotations.mapNotNull { annotation -> // 对每个注解尝试解析
            resolve(annotation)?.let { annotation to it } // 解析成功则保留 (注解节点, Info) 二元组，失败（mapNotNull）自动丢弃
        }
}

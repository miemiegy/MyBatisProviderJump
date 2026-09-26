package io.github.miemiegyy.mybatisproviderjump // 与主代码同包

import com.intellij.openapi.module.Module // 模块对象
import com.intellij.openapi.roots.ContentEntry // 模块内容根
import com.intellij.openapi.roots.ModifiableRootModel // 可修改的模块依赖模型
import com.intellij.testFramework.LightProjectDescriptor // 测试工程描述符
import com.intellij.testFramework.PsiTestUtil // PSI 测试工具（添加库等）
import com.intellij.testFramework.fixtures.BasePlatformTestCase // 平台测试基类：提供 myFixture，可在无头环境加载插件并驱动编辑器功能
import com.intellij.testFramework.fixtures.DefaultLightProjectDescriptor // 默认 Java 轻量工程描述符
import java.nio.file.Paths // 路径工具

/**
 * gutter 图标的自动化验证（无 GUI，等效于"打开文件看图标"）：
 * 每个用例构造内存中的 Provider/Mapper Java 文件，调用 findAllGutters()
 * 触发行标记收集（含 collectSlowLineMarkers），断言图标数量与悬停文案。
 */
class LineMarkerTest : BasePlatformTestCase() { // 继承平台测试基类（JUnit3 风格，方法名 testXxx）

    /**
     * 轻量测试工程默认只有 JDK，没有 mybatis —— 注解解析不了，插件会全部跳过。
     * 这里把测试 classpath 上的 mybatis jar 加为模块库，模拟真实工程的依赖环境。
     */
    override fun getProjectDescriptor(): LightProjectDescriptor = object : DefaultLightProjectDescriptor() {
        override fun configureModule(module: Module, model: ModifiableRootModel, contentEntry: ContentEntry) {
            super.configureModule(module, model, contentEntry)
            val resourceUrl = javaClass.classLoader // 从测试 classpath 定位 mybatis jar
                .getResource("org/apache/ibatis/annotations/SelectProvider.class")?.toString()
                ?: error("测试 classpath 上找不到 mybatis")
            val jarPath = Paths.get(resourceUrl.removePrefix("jar:file:").substringBefore("!/")) // jar:file:/path/xxx.jar!/org/... -> /path/xxx.jar
            PsiTestUtil.addLibrary(model, "mybatis", jarPath.parent.toString(), jarPath.fileName.toString()) // 加为模块库
        }
    }

    // ============ 正向（Mapper -> Provider，Forward 图标） ============

    /** 场景 1：显式 type + 显式 method（最基础写法）。 */
    fun testForwardWithExplicitTypeAndMethod() {
        addJavaClass("UserProvider.java", PLAIN_PROVIDER)
        myFixture.configureByText(
            "UserMapper.java",
            """
            import org.apache.ibatis.annotations.SelectProvider;

            public interface UserMapper {
                @SelectProvider(type = UserProvider.class, method = "selectById")
                String selectById();
            }
            """.trimIndent(),
        )

        val gutters = myFixture.findAllGutters()
        assertEquals("显式 type+method 应产生 1 个 Forward 图标", 1, gutters.size)
        assertTrue(
            "悬停文案应指向 Provider 方法",
            gutters.single().tooltipText?.contains("UserProvider") == true,
        )
    }

    /** 场景 2：bare 短写法 @SelectProvider(X.class)（类字面量绑定到 value 属性）+ 同名约定。 */
    fun testForwardWithBareValueSyntax() {
        addJavaClass("ResolverProvider.java", RESOLVER_PROVIDER) // bare + 省略 method + 实现 ProviderMethodResolver → 同名解析
        myFixture.configureByText(
            "UserMapper.java",
            """
            import org.apache.ibatis.annotations.SelectProvider;

            public interface UserMapper {
                @SelectProvider(ResolverProvider.class)
                String selectById();
            }
            """.trimIndent(),
        )

        val gutters = myFixture.findAllGutters()
        assertEquals("bare 短写法应产生 1 个 Forward 图标", 1, gutters.size)
    }

    /** 场景 3：同名约定——省略 method 且 Provider 实现 ProviderMethodResolver。 */
    fun testForwardWithSameNameConvention() {
        addJavaClass("ResolverProvider.java", RESOLVER_PROVIDER)
        myFixture.configureByText(
            "UserMapper.java",
            """
            import org.apache.ibatis.annotations.SelectProvider;

            public interface UserMapper {
                @SelectProvider(type = ResolverProvider.class)
                String selectById();
            }
            """.trimIndent(),
        )

        val gutters = myFixture.findAllGutters()
        assertEquals("同名约定应产生 1 个 Forward 图标", 1, gutters.size)
    }

    /** 场景 4：省略 method 且 Provider 未实现 ProviderMethodResolver → provideSql 兜底。 */
    fun testForwardProvideSqlFallback() {
        addJavaClass("LegacyProvider.java", LEGACY_PROVIDER)
        myFixture.configureByText(
            "UserMapper.java",
            """
            import org.apache.ibatis.annotations.SelectProvider;

            public interface UserMapper {
                @SelectProvider(type = LegacyProvider.class)
                String selectLegacy();
            }
            """.trimIndent(),
        )

        val gutters = myFixture.findAllGutters()
        assertEquals("provideSql 兜底应产生 1 个 Forward 图标", 1, gutters.size)
    }

    /** 负向：method 指向不存在的方法 → 不挂图标。 */
    fun testForwardNoIconWhenMethodMissing() {
        addJavaClass("UserProvider.java", PLAIN_PROVIDER)
        myFixture.configureByText(
            "UserMapper.java",
            """
            import org.apache.ibatis.annotations.SelectProvider;

            public interface UserMapper {
                @SelectProvider(type = UserProvider.class, method = "notExist")
                String selectById();
            }
            """.trimIndent(),
        )

        assertEquals("目标方法不存在时不应有图标", 0, myFixture.findAllGutters().size)
    }

    // ============ 反向（Provider -> Mapper，Back 图标） ============

    /** 场景 6：被引用的方法挂 Back 图标，未被引用的方法不挂。 */
    fun testBackwardMarkerOnlyOnReferencedMethod() {
        addJavaClass(
            "UserMapper.java",
            """
            import org.apache.ibatis.annotations.SelectProvider;

            public interface UserMapper {
                @SelectProvider(type = UserProvider.class, method = "selectById")
                String selectById();
            }
            """.trimIndent(),
        )
        myFixture.configureByText(
            "UserProvider.java",
            """
            public class UserProvider {
                public String selectById() { return ""; }
                public String helper() { return ""; }
            }
            """.trimIndent(),
        )

        val gutters = myFixture.findAllGutters()
        assertEquals("只有被引用的 selectById 应挂 1 个 Back 图标", 1, gutters.size)
        assertTrue(
            "悬停文案应指向 Mapper",
            gutters.single().tooltipText?.contains("Mapper") == true,
        )
    }

    /** 场景 7：同一个 Provider 方法被两个 Mapper 引用（多引用点，弹列表）。 */
    fun testBackwardWithMultipleReferences() {
        addJavaClass(
            "OrderMapper.java",
            """
            import org.apache.ibatis.annotations.SelectProvider;

            public interface OrderMapper {
                @SelectProvider(type = UserProvider.class, method = "buildSharedSql")
                String listByName();
            }
            """.trimIndent(),
        )
        addJavaClass(
            "LogMapper.java",
            """
            import org.apache.ibatis.annotations.SelectProvider;

            public interface LogMapper {
                @SelectProvider(type = UserProvider.class, method = "buildSharedSql")
                String listAll();
            }
            """.trimIndent(),
        )
        myFixture.configureByText(
            "UserProvider.java",
            """
            public class UserProvider {
                public String buildSharedSql() { return ""; }
            }
            """.trimIndent(),
        )

        assertEquals("多引用点仍只挂 1 个 Back 图标（点击弹列表）", 1, myFixture.findAllGutters().size)
    }

    /** 场景 8：反向 + bare 短写法（反向也必须能读 value 属性；同名约定）。 */
    fun testBackwardWithBareValueSyntax() {
        addJavaClass(
            "UserMapper.java",
            """
            import org.apache.ibatis.annotations.SelectProvider;

            public interface UserMapper {
                @SelectProvider(ResolverProvider.class)
                String selectById();
            }
            """.trimIndent(),
        )
        myFixture.configureByText(
            "ResolverProvider.java",
            RESOLVER_PROVIDER,
        )

        assertEquals("bare 短写法的反向也应挂 Back 图标", 1, myFixture.findAllGutters().size)
    }

    /** 把一个 Java 类文件加入内存工程（BasePlatformTestCase 的 fixture 是 CodeInsightTestFixture，用 addFileToProject 创建）。 */
    private fun addJavaClass(fileName: String, text: String) {
        myFixture.addFileToProject(fileName, text) // 加入源码根，类按自身类名可被解析
    }

    private companion object {
        /** 普通 Provider（显式 method 场景用）。 */
        private val PLAIN_PROVIDER =
            """
            public class UserProvider {
                public String selectById() { return ""; }
            }
            """.trimIndent()

        /** 实现 ProviderMethodResolver 的 Provider（同名约定场景用）。 */
        private val RESOLVER_PROVIDER =
            """
            import org.apache.ibatis.builder.annotation.ProviderMethodResolver;

            public class ResolverProvider implements ProviderMethodResolver {
                public String selectById() { return ""; }
            }
            """.trimIndent()

        /** 未实现 ProviderMethodResolver 的 Provider（provideSql 兜底场景用）。 */
        private val LEGACY_PROVIDER =
            """
            public class LegacyProvider {
                public String provideSql() { return ""; }
            }
            """.trimIndent()
    }
}

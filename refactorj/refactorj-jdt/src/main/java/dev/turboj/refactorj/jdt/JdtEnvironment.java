package dev.turboj.refactorj.jdt;

import org.eclipse.core.runtime.preferences.DefaultScope;
import org.eclipse.jdt.core.manipulation.CodeStyleConfiguration;
import org.eclipse.jdt.core.manipulation.JavaManipulation;
import org.eclipse.jdt.internal.core.manipulation.CodeTemplateContextType;
import org.eclipse.jdt.internal.core.manipulation.MembersOrderPreferenceCacheCommon;
import org.eclipse.jdt.internal.corext.codemanipulation.CodeGenerationSettingsConstants;
import org.eclipse.jface.text.templates.Template;
import org.eclipse.text.templates.ContextTypeRegistry;
import org.eclipse.text.templates.TemplatePersistenceData;
import org.eclipse.text.templates.TemplateStoreCore;

/** Deterministic headless generation settings; no Eclipse UI preference store is initialized. */
public final class JdtEnvironment {
    private static boolean initialized;
    private JdtEnvironment() {}

    public static synchronized void initialize() {
        if (initialized) return;
        String node = "dev.turboj.refactorj";
        JavaManipulation.setPreferenceNodeId(node);
        var preferences = DefaultScope.INSTANCE.getNode(node);
        preferences.put(CodeStyleConfiguration.ORGIMPORTS_IMPORTORDER, "#;");
        preferences.putInt(CodeStyleConfiguration.ORGIMPORTS_ONDEMANDTHRESHOLD, Integer.MAX_VALUE);
        preferences.putInt(CodeStyleConfiguration.ORGIMPORTS_STATIC_ONDEMANDTHRESHOLD, Integer.MAX_VALUE);
        preferences.putBoolean(CodeGenerationSettingsConstants.CODEGEN_ADD_COMMENTS, false);
        preferences.putBoolean(CodeGenerationSettingsConstants.CODEGEN_USE_OVERRIDE_ANNOTATION, true);
        preferences.putBoolean(CodeGenerationSettingsConstants.CODEGEN_KEYWORD_THIS, false);
        preferences.putBoolean(CodeGenerationSettingsConstants.ORGIMPORTS_IGNORELOWERCASE, true);
        // Organize Imports filters every type-name match through this list; unset, it throws on the first unresolved type.
        preferences.put("org.eclipse.jdt.ui.typefilter.enabled", "");
        preferences.put(MembersOrderPreferenceCacheCommon.APPEARANCE_MEMBER_SORT_ORDER, "SF,SI,F,I,C,SM,M,T");
        preferences.put(MembersOrderPreferenceCacheCommon.APPEARANCE_VISIBILITY_SORT_ORDER, "B,R,D,V");
        preferences.putBoolean(MembersOrderPreferenceCacheCommon.APPEARANCE_ENABLE_VISIBILITY_SORT_ORDER, false);
        new MembersOrderPreferenceCacheCommon().install();
        ContextTypeRegistry contexts = new ContextTypeRegistry();
        CodeTemplateContextType.registerContextTypes(contexts);
        JavaManipulation.setCodeTemplateContextRegistry(contexts);
        TemplateStoreCore templates = new TemplateStoreCore(contexts, preferences, "codeTemplates");
        template(templates, CodeTemplateContextType.METHODSTUB_ID, CodeTemplateContextType.METHODBODY_CONTEXTTYPE, "${body_statement}");
        template(templates, CodeTemplateContextType.METHODSTUB_ALTERNATIVE_ID, CodeTemplateContextType.METHODBODY_CONTEXTTYPE, "${body_statement}");
        template(templates, CodeTemplateContextType.CONSTRUCTORSTUB_ID, CodeTemplateContextType.CONSTRUCTORBODY_CONTEXTTYPE, "${body_statement}");
        template(templates, CodeTemplateContextType.GETTERSTUB_ID, CodeTemplateContextType.GETTERBODY_CONTEXTTYPE, "return ${field};");
        template(templates, CodeTemplateContextType.SETTERSTUB_ID, CodeTemplateContextType.SETTERBODY_CONTEXTTYPE, "${field} = ${param};");
        JavaManipulation.setCodeTemplateStore(templates);
        initialized = true;
    }

    private static void template(TemplateStoreCore store, String id, String context, String body) {
        store.add(new TemplatePersistenceData(new Template(id, "RefactorJ generated code", context, body, false), true, id));
    }
}

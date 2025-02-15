package com.intellij.workspaceModel.codegen.classes

import com.intellij.workspaceModel.storage.CodeGeneratorVersions
import com.intellij.workspaceModel.storage.GeneratedCodeApiVersion
import com.intellij.workspaceModel.storage.GeneratedCodeImplVersion
import com.intellij.workspaceModel.storage.impl.ConnectionId
import com.intellij.workspaceModel.storage.impl.WorkspaceEntityBase
import com.intellij.workspaceModel.codegen.indentRestOnly
import com.intellij.workspaceModel.codegen.javaFullName
import com.intellij.workspaceModel.codegen.javaImplName
import com.intellij.workspaceModel.codegen.lines
import com.intellij.workspaceModel.codegen.allRefsFields
import com.intellij.workspaceModel.codegen.fields.implWsEntityFieldCode
import com.intellij.workspaceModel.codegen.fields.refsConnectionIdCode
import com.intellij.workspaceModel.codegen.utils.fqn
import com.intellij.workspaceModel.codegen.deft.meta.ObjClass
import com.intellij.workspaceModel.codegen.fields.refsConnectionId
import com.intellij.workspaceModel.codegen.utils.lines
import com.intellij.workspaceModel.codegen.writer.allFields

fun ObjClass<*>.implWsEntityCode(): String {
  return """
package ${module.name}    

@${GeneratedCodeApiVersion::class.fqn}(${CodeGeneratorVersions.API_VERSION})
@${GeneratedCodeImplVersion::class.fqn}(${CodeGeneratorVersions.IMPL_VERSION})
${if (openness.instantiatable) "open" else "abstract"} class $javaImplName: $javaFullName, ${WorkspaceEntityBase::class.fqn}() {
    ${
    """
    companion object {
        ${allRefsFields.lines("        ") { refsConnectionIdCode }.trimEnd()}
        
${getLinksOfConnectionIds(this)}
    }"""
  }
        
    ${allFields.filter { it.name !in listOf("entitySource", "persistentId") }.lines("    ") { implWsEntityFieldCode }.trimEnd()}
    
    override fun connectionIdList(): List<${ConnectionId::class.fqn}> {
        return connections
    }

    ${implWsEntityBuilderCode().indentRestOnly("    ")}
}
    """.trimIndent()
}

private fun getLinksOfConnectionIds(type: ObjClass<*>): String {
  return lines(2) {
    line("val connections = listOf<${ConnectionId::class.fqn}>(")
    type.allRefsFields.forEach {
      line("    " + it.refsConnectionId + ",")
    }
    line(")")
  }
}

package com.nocrunch.rider.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.fileTypes.FileTypeManager
import javax.swing.Icon

/** Rider's own icons for what a tree row is, falling back to platform icons if Rider's icon classes change. */
object KindIcons {
    private fun rider(get: () -> Icon, fallback: Icon): Icon = runCatching(get).getOrNull() ?: fallback

    val project: Icon by lazy { rider({ icons.ReSharperIcons.ProjectModel.CsharpProject }, AllIcons.Nodes.Module) }
    val namespace: Icon by lazy { rider({ icons.ReSharperIcons.PsiSymbols.Namespace }, AllIcons.Nodes.Package) }
    val folder: Icon by lazy { rider({ icons.ReSharperIcons.ProjectModel.Folder }, AllIcons.Nodes.Folder) }
    /** A class, theory or group of data rows. */
    val testSet: Icon by lazy { rider({ icons.ReSharperIcons.PsiSymbols.SymbolUnitTestSet }, AllIcons.RunConfigurations.TestNotRan) }
    val test: Icon by lazy { rider({ icons.ReSharperIcons.PsiSymbols.SymbolUnitTest }, AllIcons.RunConfigurations.TestNotRan) }

    /** Status for rows excluded from automatic runs. */
    val excluded: Icon by lazy { rider({ icons.ReSharperIcons.UnitTesting.StatusIgnored }, AllIcons.RunConfigurations.TestIgnored) }

    fun file(name: String): Icon = FileTypeManager.getInstance().getFileTypeByFileName(name).icon ?: AllIcons.FileTypes.Any_type
}

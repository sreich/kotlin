/*
 * Copyright 2010-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.cli.jvm.compiler;

import com.intellij.core.*;
import com.intellij.mock.MockFileIndexFacade;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.roots.LanguageLevelProjectExtension;
import com.intellij.openapi.roots.PackageIndex;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiElementFactory;
import com.intellij.psi.PsiResolveHelper;
import com.intellij.psi.codeStyle.JavaCodeStyleManager;
import com.intellij.psi.codeStyle.JavaCodeStyleSettingsFacade;
import com.intellij.psi.controlFlow.ControlFlowFactory;
import com.intellij.psi.impl.JavaPsiFacadeImpl;
import com.intellij.psi.impl.JavaPsiImplementationHelper;
import com.intellij.psi.impl.PsiElementFactoryImpl;
import com.intellij.psi.impl.file.impl.JavaFileManager;
import com.intellij.psi.impl.source.resolve.JavaResolveCache;
import com.intellij.psi.impl.source.resolve.PsiResolveHelperImpl;
import org.jetbrains.annotations.NotNull;

public class KotlinCoreProjectEnvironment extends CoreProjectEnvironment {
    private final PackageIndex myPackageIndex;

    public KotlinCoreProjectEnvironment(Disposable parentDisposable, CoreApplicationEnvironment applicationEnvironment) {
        super(parentDisposable, applicationEnvironment);

        myProject.registerService(PsiElementFactory.class, new PsiElementFactoryImpl(myPsiManager));
        myProject.registerService(JavaPsiImplementationHelper.class, new CoreJavaPsiImplementationHelper(myProject));
        myProject.registerService(PsiResolveHelper.class, new PsiResolveHelperImpl(myPsiManager));
        myProject.registerService(LanguageLevelProjectExtension.class, new CoreLanguageLevelProjectExtension());
        myProject.registerService(JavaResolveCache.class, new JavaResolveCache(myMessageBus));
        myProject.registerService(JavaCodeStyleSettingsFacade.class, new CoreJavaCodeStyleSettingsFacade());
        myProject.registerService(JavaCodeStyleManager.class, new CoreJavaCodeStyleManager());
        myProject.registerService(ControlFlowFactory.class, new ControlFlowFactory(myPsiManager));

        myPackageIndex = new CorePackageIndex();
        myProject.registerService(PackageIndex.class, myPackageIndex);

        JavaFileManager myFileManager = new KotlinCliJavaFileManagerImpl(myPsiManager);
        myProject.registerService(JavaFileManager.class, myFileManager);

        JavaPsiFacadeImpl javaPsiFacade = new JavaPsiFacadeImpl(myProject, myPsiManager, myFileManager, myMessageBus);
        myProject.registerService(JavaPsiFacade.class, javaPsiFacade);
    }

    public void addSourcesToClasspath(@NotNull VirtualFile root) {
        assert root.isDirectory() : "Not a directory: " + root;
        ((CorePackageIndex) myPackageIndex).addToClasspath(root);
        ((MockFileIndexFacade) myFileIndexFacade).addLibraryRoot(root);
    }
}

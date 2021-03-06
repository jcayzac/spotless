/*
 * Copyright 2016 DiffPlug
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.diffplug.gradle.spotless;

import static com.diffplug.gradle.spotless.Tasks.execute;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;

import org.gradle.api.Project;
import org.junit.Assert;
import org.junit.Test;

import com.diffplug.common.base.StandardSystemProperty;
import com.diffplug.common.base.StringPrinter;
import com.diffplug.spotless.FormatterFunc;
import com.diffplug.spotless.FormatterStep;
import com.diffplug.spotless.LineEnding;
import com.diffplug.spotless.ResourceHarness;
import com.diffplug.spotless.TestProvisioner;

public class PaddedCellTaskTest extends ResourceHarness {
	private static final boolean IS_WIN = StandardSystemProperty.OS_NAME.value().toLowerCase(Locale.US).contains("win");

	private static String slashify(String input) {
		return IS_WIN ? input.replace('/', '\\') : input;
	}

	private class Bundle {
		String name;
		Project project = TestProvisioner.gradleProject(rootFolder());
		File file;
		SpotlessTask check;
		SpotlessTask apply;

		Bundle(String name, FormatterFunc function) throws IOException {
			this.name = name;
			file = setFile("src/test." + name).toContent("CCC");
			FormatterStep step = FormatterStep.createNeverUpToDate(name, function);
			check = createCheckTask(name, step);
			apply = createApplyTask(name, step);
		}

		private SpotlessTask createCheckTask(String name, FormatterStep step) {
			// we don't add Check to the end because SpotlessTask normally doesn't have
			// "Check" or "Apply", and it matters for generating the failure files
			SpotlessTask task = project.getTasks().create("spotless" + SpotlessPlugin.capitalize(name), SpotlessTask.class);
			task.setCheck();
			task.addStep(step);
			task.setLineEndingsPolicy(LineEnding.UNIX.createPolicy());
			task.setTarget(Collections.singletonList(file));
			return task;
		}

		private SpotlessTask createApplyTask(String name, FormatterStep step) {
			SpotlessTask task = project.getTasks().create("spotless" + SpotlessPlugin.capitalize(name) + "Apply", SpotlessTask.class);
			task.setApply();
			task.addStep(step);
			task.setLineEndingsPolicy(LineEnding.UNIX.createPolicy());
			task.setTarget(Collections.singletonList(file));
			return task;
		}

		private String checkFailureMsg() {
			try {
				execute(check);
				throw new AssertionError();
			} catch (Exception e) {
				return e.getMessage();
			}
		}

		private void diagnose() throws IOException {
			SpotlessDiagnoseTask diagnose = project.getTasks().create("spotless" + SpotlessPlugin.capitalize(name) + "Diagnose", SpotlessDiagnoseTask.class);
			diagnose.source = check;
			diagnose.performAction();
		}
	}

	private Bundle wellbehaved() throws IOException {
		return new Bundle("wellbehaved", x -> "42");
	}

	private Bundle cycle() throws IOException {
		return new Bundle("cycle", x -> x.equals("A") ? "B" : "A");
	}

	private Bundle converge() throws IOException {
		return new Bundle("converge", x -> x.isEmpty() ? x : x.substring(0, x.length() - 1));
	}

	private Bundle diverge() throws IOException {
		return new Bundle("diverge", x -> x + " ");
	}

	@Test
	public void paddedCellApply() throws Exception {
		Bundle wellbehaved = wellbehaved();
		Bundle cycle = cycle();
		Bundle converge = converge();
		Bundle diverge = diverge();

		execute(wellbehaved.apply);
		execute(cycle.apply);
		execute(converge.apply);
		execute(diverge.apply);

		assertFile(wellbehaved.file).hasContent("42");	// cycle -> first element in cycle
		assertFile(cycle.file).hasContent("A");		// cycle -> first element in cycle
		assertFile(converge.file).hasContent("");	// converge -> converges
		assertFile(diverge.file).hasContent("CCC");	// diverge -> no change

		execute(wellbehaved.check);
		execute(cycle.check);
		execute(converge.check);
		execute(diverge.check);
	}

	@Test
	public void diagnose() throws Exception {
		wellbehaved().diagnose();
		cycle().diagnose();
		converge().diagnose();
		diverge().diagnose();

		assertFolderContents("build",
				"spotless-diagnose-converge",
				"spotless-diagnose-cycle",
				"spotless-diagnose-diverge");
		assertFolderContents("build/spotless-diagnose-cycle/src",
				"test.cycle.cycle0",
				"test.cycle.cycle1");
		assertFolderContents("build/spotless-diagnose-converge/src",
				"test.converge.converge0",
				"test.converge.converge1",
				"test.converge.converge2");
		assertFolderContents("build/spotless-diagnose-diverge/src",
				"test.diverge.diverge0",
				"test.diverge.diverge1",
				"test.diverge.diverge2",
				"test.diverge.diverge3",
				"test.diverge.diverge4",
				"test.diverge.diverge5",
				"test.diverge.diverge6",
				"test.diverge.diverge7",
				"test.diverge.diverge8",
				"test.diverge.diverge9");
	}

	private void assertFolderContents(String subfolderName, String... files) throws IOException {
		File subfolder = new File(rootFolder(), subfolderName);
		Assert.assertTrue(subfolder.isDirectory());
		String asList = String.join("\n", Arrays.asList(files));
		Assert.assertEquals(StringPrinter.buildStringFromLines(files).trim(), asList);
	}

	@Test
	public void paddedCellCheckCycleFailureMsg() throws IOException {
		assertFailureMessage(cycle(),
				"The following files had format violations:",
				slashify("    src/test.cycle"),
				"        @@ -1 +1 @@",
				"        -CCC",
				"        +A",
				"Run 'gradlew spotlessApply' to fix these violations.");
	}

	@Test
	public void paddedCellCheckConvergeFailureMsg() throws IOException {
		assertFailureMessage(converge(),
				"The following files had format violations:",
				slashify("    src/test.converge"),
				"        @@ -1 +0,0 @@",
				"        -CCC",
				"Run 'gradlew spotlessApply' to fix these violations.");
	}

	private void assertFailureMessage(Bundle bundle, String... expectedOutput) {
		String msg = bundle.checkFailureMsg();
		String expected = StringPrinter.buildStringFromLines(expectedOutput).trim();
		Assert.assertEquals(expected, msg);
	}
}

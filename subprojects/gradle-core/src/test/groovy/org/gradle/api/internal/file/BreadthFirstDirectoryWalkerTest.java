/*
 * Copyright 2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.gradle.api.internal.file;

import org.gradle.api.file.FileVisitDetails;
import org.gradle.api.file.RelativePath;
import org.gradle.api.internal.file.copy.FileCopyVisitor;
import org.gradle.api.tasks.util.PatternSet;
import org.gradle.api.specs.Spec;
import org.hamcrest.BaseMatcher;
import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.jmock.Expectations;
import org.jmock.Mockery;
import org.jmock.Sequence;
import org.jmock.api.Action;
import org.jmock.api.Invocation;
import org.jmock.integration.junit4.JMock;
import org.jmock.integration.junit4.JUnit4Mockery;
import org.jmock.lib.legacy.ClassImposteriser;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@RunWith(JMock.class)
public class BreadthFirstDirectoryWalkerTest {
    private JUnit4Mockery context = new JUnit4Mockery() {{
        setImposteriser(ClassImposteriser.INSTANCE);
    }};
    private FileCopyVisitor visitor;
    private BreadthFirstDirectoryWalker walker;

    @Before
    public void setUp() {
        visitor = context.mock(FileCopyVisitor.class);
    }

    @Test public void rootDirEmpty() throws IOException {
        final MockFile root = new MockFile(context, "root", false);

        walker = new BreadthFirstDirectoryWalker(visitor);
        root.setExpectations();

        walker.start(root.getMock());
    }

    @Test public void testUsesSpecFromPatternSetToMatchFilesAndDirs() {
        final PatternSet patternSet = context.mock(PatternSet.class);
        final Spec spec = context.mock(Spec.class);

        context.checking(new Expectations(){{
            one(patternSet).getAsSpec();
            will(returnValue(spec));
        }});

        walker = new BreadthFirstDirectoryWalker(visitor);
        walker.match(patternSet);
    }

    @Test public void walkSingleFile() throws IOException {
        walker = new BreadthFirstDirectoryWalker(visitor);

        final MockFile root = new MockFile(context, "root", false);
        final MockFile fileToCopy = root.addFile("file.txt");

        fileToCopy.setExpectations();

        context.checking(new Expectations() {{
            one(visitor).visitFile(with(file(fileToCopy)));
        }});

        walker.start(fileToCopy.getMock());
    }

    /*
    mock file structure:
    root
        rootFile1
        dir1
           dirFile1
           dirFile2
        rootFile2

        Test that the files are really walked breadth first
     */
    @Test public void walkBreadthFirst() throws IOException {

        walker = new BreadthFirstDirectoryWalker(visitor);

        final MockFile root = new MockFile(context, "root", false);
        final MockFile rootFile1 = root.addFile("rootFile1");
        final MockFile dir1 = root.addDir("dir1");
        final MockFile dirFile1 = dir1.addFile("dirFile1");
        final MockFile dirFile2 = dir1.addFile("dirFile2");
        final MockFile rootFile2 = root.addFile("rootFile2");
        root.setExpectations();

        final Sequence visiting = context.sequence("visiting");
        context.checking(new Expectations() {{
            one(visitor).visitFile(with(file(rootFile1))); inSequence(visiting);
            one(visitor).visitFile(with(file(rootFile2))); inSequence(visiting);
            one(visitor).visitDir(with(file(dir1))); inSequence(visiting);
            one(visitor).visitFile(with(file(dirFile1))); inSequence(visiting);
            one(visitor).visitFile(with(file(dirFile2))); inSequence(visiting);
        }});

        walker.start(root.getMock());
    }

    @Test public void canVisitorCanStopVisit() throws IOException {

        walker = new BreadthFirstDirectoryWalker(visitor);

        final MockFile root = new MockFile(context, "root", false);
        final MockFile rootFile1 = root.addFile("rootFile1");
        final MockFile dir1 = root.addDir("dir1");
        final MockFile dirFile1 = dir1.addFile("dirFile1");
        dir1.addFile("dirFile2");
        dir1.addDir("dir1Dir").addFile("dir1Dir1File1");
        final MockFile rootFile2 = root.addFile("rootFile2");
        root.setExpectations();

        context.checking(new Expectations() {{
            one(visitor).visitFile(with(file(rootFile1))); will(stopVisiting());
        }});

        walker.start(root.getMock());

        final Sequence visiting = context.sequence("visiting");
        context.checking(new Expectations() {{
            one(visitor).visitFile(with(file(rootFile1))); inSequence(visiting);
            one(visitor).visitFile(with(file(rootFile2))); inSequence(visiting);
            one(visitor).visitDir(with(file(dir1))); inSequence(visiting);
            one(visitor).visitFile(with(file(dirFile1))); will(stopVisiting()); inSequence(visiting);
        }});

        walker.start(root.getMock());
    }

    private Action stopVisiting() {
        return new Action() {
            public void describeTo(Description description) {
                description.appendText("stop visiting");
            }

            public Object invoke(Invocation invocation) throws Throwable {
                FileVisitDetails details = (FileVisitDetails) invocation.getParameter(0);
                details.stopVisiting();
                return null;
            }
        };
    }

    // test excludes, includes

    private Matcher<FileVisitDetails> file(final MockFile file) {
        return new BaseMatcher<FileVisitDetails>() {
            public boolean matches(Object o) {
                FileVisitDetails details = (FileVisitDetails) o;
                return details.getFile().equals(file.getMock()) && details.getRelativePath().equals(file.getRelativePath());
            }

            public void describeTo(Description description) {
                description.appendText("details match file ").appendValue(file.getMock()).appendText(" with path ")
                        .appendValue(file.getRelativePath());
            }
        };
    }

    public class MockFile {
        private boolean isFile;
        private String name;
        private Mockery context;
        private List<MockFile> children;
        private File mock;
        private MockFile parent;

        public MockFile(Mockery context, String name, boolean isFile) {
            this.context = context;
            this.name = name;
            this.isFile = isFile;
            children = new ArrayList<MockFile>();
            mock = context.mock(File.class, name);
        }

        public File getMock() {
            return mock;
        }

        public MockFile addFile(String name) {
            MockFile child = new MockFile(context, name, true);
            child.setParent(this);
            children.add(child);
            return child;
        }

        public MockFile addDir(String name) {
            MockFile child = new MockFile(context, name, false);
            child.setParent(this);
            children.add(child);
            return child;
        }

        public void setParent(MockFile parent) {
            this.parent = parent;
        }

        public RelativePath getRelativePath() {
            if (parent == null) {
                return new RelativePath(isFile);
            } else {
                return parent.getRelativePath().append(isFile, name);
            }
        }

        public void setExpectations() {
            Expectations expectations = new Expectations();
            setExpectations(expectations);
            context.checking(expectations);
        }

        public void setExpectations(Expectations expectations) {
            try {
                expectations.allowing(mock).getCanonicalFile();
                expectations.will(expectations.returnValue(mock));
            } catch (Throwable th){};
            expectations.allowing(mock).isFile();
            expectations.will(expectations.returnValue(isFile));
            expectations.allowing(mock).getName();
            expectations.will(expectations.returnValue(name));
            expectations.allowing(mock).exists();
            expectations.will(expectations.returnValue(true));

            ArrayList<File> mockChildren = new ArrayList<File>(children.size());
            for (MockFile child : children) {
                mockChildren.add(child.getMock());
                child.setExpectations(expectations);
            }
            expectations.allowing(mock).listFiles();
            expectations.will(expectations.returnValue(mockChildren.toArray(new File[mockChildren.size()])));
        }
    }

}

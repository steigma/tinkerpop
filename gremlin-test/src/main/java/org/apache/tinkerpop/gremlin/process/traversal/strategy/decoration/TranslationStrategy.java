/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */

package org.apache.tinkerpop.gremlin.process.traversal.strategy.decoration;

import org.apache.tinkerpop.gremlin.jsr223.GremlinScriptEngine;
import org.apache.tinkerpop.gremlin.jsr223.SingleGremlinScriptEngineManager;
import org.apache.tinkerpop.gremlin.process.remote.traversal.strategy.decoration.RemoteStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.Step;
import org.apache.tinkerpop.gremlin.process.traversal.Translator;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.TraversalSource;
import org.apache.tinkerpop.gremlin.process.traversal.TraversalStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.step.util.EmptyStep;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.AbstractTraversalStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.verification.VerificationException;
import org.apache.tinkerpop.gremlin.process.traversal.util.TraversalHelper;

import javax.script.Bindings;
import javax.script.ScriptContext;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * @author Marko A. Rodriguez (http://markorodriguez.com)
 */
public final class TranslationStrategy extends AbstractTraversalStrategy<TraversalStrategy.DecorationStrategy> implements TraversalStrategy.DecorationStrategy {

    private final TraversalSource traversalSource;
    private final Translator translator;

    private static final Set<Class<? extends DecorationStrategy>> POSTS = new HashSet<>(Arrays.asList(
            ConnectiveStrategy.class,
            ElementIdStrategy.class,
            EventStrategy.class,
            HaltedTraverserStrategy.class,
            PartitionStrategy.class,
            RequirementsStrategy.class,
            SackStrategy.class,
            SideEffectStrategy.class,
            SubgraphStrategy.class,
            RemoteStrategy.class));

    public TranslationStrategy(final TraversalSource traversalSource, final Translator translator) {
        this.traversalSource = traversalSource;
        this.translator = translator;
    }

    @Override
    public void apply(final Traversal.Admin<?, ?> traversal) {
        if (!(traversal.getParent() instanceof EmptyStep))
            return;

        // verifications to ensure unsupported steps do not exist in the traversal
        if (Boolean.valueOf(System.getProperty("is.testing", "false")) &&
                (traversal.getBytecode().toString().contains("$") || traversal.getBytecode().toString().contains("HashSetSupplier")))
            throw new VerificationException("Test suite does not support profiling nor lambdas", traversal);

        final Traversal.Admin<?, ?> translatedTraversal;
        ////////////////
        if (this.translator instanceof Translator.StepTranslator) {
            // reflection based translation
            translatedTraversal = (Traversal.Admin<?, ?>) this.translator.translate(traversal.getBytecode());
        } else if (this.translator instanceof Translator.ScriptTranslator) {
            try {
                // script based translation
                final GremlinScriptEngine scriptEngine = SingleGremlinScriptEngineManager.get(this.translator.getTargetLanguage());
                final Bindings bindings = scriptEngine.createBindings();
                bindings.putAll(scriptEngine.getContext().getBindings(ScriptContext.ENGINE_SCOPE));
                bindings.put(this.translator.getTraversalSource().toString(), this.traversalSource);
                translatedTraversal = (Traversal.Admin<?, ?>) scriptEngine.eval(traversal.getBytecode(), bindings);
            } catch (final Exception e) {
                throw new IllegalArgumentException(e.getMessage(), e);
            }
        } else {
            throw new IllegalArgumentException("TranslationStrategy does not know how to process the provided translator type: " + this.translator.getClass().getSimpleName());
        }
        ////////////////
        assert !translatedTraversal.isLocked();
        assert !traversal.isLocked();
        traversal.setSideEffects(translatedTraversal.getSideEffects());
        TraversalHelper.removeAllSteps(traversal);
        TraversalHelper.removeToTraversal((Step) translatedTraversal.getStartStep(), EmptyStep.instance(), traversal);
    }


    @Override
    public Set<Class<? extends DecorationStrategy>> applyPost() {
        return POSTS;
    }

}

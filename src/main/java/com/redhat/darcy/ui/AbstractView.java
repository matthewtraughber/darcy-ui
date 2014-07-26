/*
 Copyright 2014 Red Hat, Inc. and/or its affiliates.

 This file is part of darcy.

 This program is free software: you can redistribute it and/or modify
 it under the terms of the GNU General Public License as published by
 the Free Software Foundation, either version 3 of the License, or
 (at your option) any later version.

 This program is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.redhat.darcy.ui;

import static com.redhat.darcy.ui.matchers.ElementMatchers.isDisplayed;

import com.redhat.darcy.ui.annotations.Context;
import com.redhat.darcy.ui.annotations.NotRequired;
import com.redhat.darcy.ui.annotations.Require;
import com.redhat.darcy.ui.annotations.RequireAll;
import com.redhat.darcy.ui.api.ElementContext;
import com.redhat.darcy.ui.api.HasElementContext;
import com.redhat.darcy.ui.api.Transition;
import com.redhat.darcy.ui.api.View;
import com.redhat.darcy.ui.api.elements.Element;
import com.redhat.darcy.ui.internal.InheritsContext;
import com.redhat.darcy.ui.matchers.ViewMatchers;
import com.redhat.darcy.util.ReflectionUtil;
import com.redhat.synq.Condition;
import com.redhat.synq.HamcrestCondition;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * A partial implementation of View that initializes LazyElements in
 * {@link #setContext(com.redhat.darcy.ui.api.ElementContext)}, and simplifies defining load
 * conditions (via {@link Require}, {@link RequireAll}, {@link NotRequired}, and
 * {@link #loadCondition()}.
 *
 * @see #setContext(com.redhat.darcy.ui.api.ElementContext)
 * @see #loadCondition()
 * @see #onSetContext()
 */
public abstract class AbstractView implements View {
    /**
     * The ElementContext for this View, managed by AbstractView.
     */
    private ElementContext context;

    /**
     * All of these need to evaluate to true for the View to be considered loaded.
     */
    private final List<Condition<?>> loadConditions = new ArrayList<>();

    // Initialize the load conditions
    {
        if (loadCondition() != null) {
            loadConditions.add(loadCondition());
        }
    }

    @Override
    public final boolean isLoaded() {
        if (context == null) {
            throw new NullContextException();
        }

        if (loadConditions.isEmpty()) {
            throw new MissingLoadConditionException(this);
        }

        for (Condition<?> condition : loadConditions) {
            if (!condition.isMet()) {
                return false;
            }
        }

        return true;
    }

    /**
     * In AbstractView, setContext triggers some helpful initializations:
     * <ul>
     * <li>If a field is annotated with {@link Context}, then the context parameter will be casted
     * and assigned to that field. If the context does not implement that fields type, a
     * {@link ClassCastException} will be thrown.</li>
     * <li>If there are fields that implement {@link com.redhat.darcy.ui.internal.InheritsContext}, then they
     * were created in such a way that they do not know about their owning View and, therefore,
     * ElementContext. When setContext is called, LazyElements will get the context assigned to
     * them.</li>
     * <li>If there are {@link Require}, {@link RequireAll}, or {@link NotRequired} annotations,
     * appropriate load conditions will be constructed and placed in {@link #loadConditions}.</li>
     * <li>Calls {@link #onSetContext()} so that implementations of AbstractView may provide their
     * own initializations that depend on the context, as necessary.</li>
     * </ul>
     */
    @Override
    public void setContext(ElementContext context) {
        List<Field> fields = ReflectionUtil.getAllDeclaredFields(this);

        if (this.context == null) { // This only needs to happen once
            readLoadConditionAnnotations(fields);
        }

        this.context = context;

        assignAndCastContextToFieldsAnnotatedWithContext(fields);
        setContextOnLazyElements(fields);

        if (loadConditions.isEmpty()) {
            throw new MissingLoadConditionException(this);
        }

        onSetContext();
    }

    @Override
    public final ElementContext getContext() {
        return context;
    }

    /**
     * Used by {@link #isLoaded()}. When the Callable.call evaluates to true, the page should be
     * loaded.
     * <P>
     * This condition will be considered in addition to any elements annotated with {@link Require}.
     * <P>
     * By default this returns null. Subclasses should override this method if necessary to define a
     * more specific load condition. If the simple visibility of some elements is all that is
     * required, then simply use {@link Require} or {@link RequireAll} annotations.
     *
     * @return Null if not explicitly overridden by a subclass.
     */
    protected Condition<?> loadCondition() {
        return null;
    }

    /**
     * Called after any call to {@link #setContext(ElementContext)}. Useful if you need to set up
     * some fields that depend on this view having context.
     */
    protected void onSetContext() {

    }

    /**
     * Shortcut for getContext().transition().
     * @see ElementContext#transition()
     * @return
     */
    protected Transition transition() {
        return context.transition();
    }

    private void assignAndCastContextToFieldsAnnotatedWithContext(List<Field> fields) {
        fields.stream()
            .filter(f -> f.getAnnotation(Context.class) != null)
            .forEach(f -> {
                try {
                    f.set(this, f.getType().cast(context));
                } catch (IllegalAccessException e) {
                    throw new RuntimeException(e);
                }
            });
    }

    private void setContextOnLazyElements(List<Field> fields) {
        fields.stream()
            .filter(f -> Element.class.isAssignableFrom(f.getType())
                    || List.class.isAssignableFrom(f.getType()))
            .map(f -> {
                try {
                    return f.get(this);
                } catch (IllegalAccessException e) {
                    throw new RuntimeException(e);
                }
            })
            .filter(o -> o instanceof HasElementContext)
            .map(e -> (HasElementContext) e)
            .forEach(e -> e.setContext(getContext()));
    }

    private void readLoadConditionAnnotations(List<Field> fields) {
        loadConditions.addAll(fields.stream()
            .filter(f -> Element.class.isAssignableFrom(f.getType()))
            .map(this::getLoadConditionForElementField)
            .filter(c -> c != null)
            .collect(Collectors.toList()));
    }

    private Condition<?> getLoadConditionForElementField(Field field) {
        try {
            Object element = field.get(this);

            Condition<?> loadCondition;

            // Determine the applicable condition for this type; prefer View over Element
            if (element instanceof View) {
                loadCondition = HamcrestCondition.match((View) element, ViewMatchers.isLoaded());
            } else if (element instanceof Element) {
                loadCondition = HamcrestCondition.match((Element) element, isDisplayed());
            } else {
                return null;
            }

            // Annotation logic
            if (field.getAnnotation(Require.class) != null
                    || (field.getDeclaringClass().getAnnotation(RequireAll.class) != null
                    && field.getAnnotation(NotRequired.class) == null)) {
                return loadCondition;
            }

            return null;
        } catch (IllegalArgumentException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }
}

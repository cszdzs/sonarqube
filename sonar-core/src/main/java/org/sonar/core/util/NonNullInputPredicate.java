/*
 * SonarQube, open source software quality management tool.
 * Copyright (C) 2008-2014 SonarSource
 * mailto:contact AT sonarsource DOT com
 *
 * SonarQube is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * SonarQube is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */

package org.sonar.core.util;

import com.google.common.base.Predicate;

import javax.annotation.Nullable;

import static com.google.common.base.Preconditions.checkArgument;

/**
 * Guava Predicate that does not accept null input elements
 * @since 5.2
 */
public abstract class NonNullInputPredicate<T> implements Predicate<T> {

  @Override
  public final boolean apply(@Nullable T input) {
    checkArgument(input != null, "Null inputs are not allowed in this predicate");
    return doApply(input);
  }

  /**
   * This method is the same as {@link #apply(Object)} except that the input argument
   * is not marked as nullable
   */
  protected abstract boolean doApply(T input);
}
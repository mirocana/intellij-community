/*
 * Copyright 2000-2017 JetBrains s.r.o.
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
package com.jetbrains.python.psi.types;

import com.intellij.openapi.extensions.Extensions;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiNamedElement;
import com.intellij.util.ArrayUtil;
import com.intellij.util.containers.ContainerUtil;
import com.jetbrains.python.PyNames;
import com.jetbrains.python.psi.*;
import com.jetbrains.python.psi.impl.PyBuiltinCache;
import com.jetbrains.python.psi.impl.PyCallExpressionHelper;
import com.jetbrains.python.psi.impl.PyTypeProvider;
import com.jetbrains.python.psi.resolve.PyResolveContext;
import com.jetbrains.python.psi.resolve.RatedResolveResult;
import one.util.streamex.StreamEx;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

import static com.jetbrains.python.psi.PyUtil.as;

/**
 * @author vlan
 */
public class PyTypeChecker {
  private PyTypeChecker() {
  }

  public static boolean match(@Nullable PyType expected, @Nullable PyType actual, @NotNull TypeEvalContext context) {
    return match(expected, actual, context, null, true);
  }

  /**
   * Checks whether a type *actual* can be placed where *expected* is expected.
   * For example int matches object, while str doesn't match int.
   * Work for builtin types, classes, tuples etc.
   *
   * @param expected      expected type
   * @param actual        type to be matched against expected
   * @param context
   * @param substitutions
   * @return
   */
  public static boolean match(@Nullable PyType expected, @Nullable PyType actual, @NotNull TypeEvalContext context,
                              @Nullable Map<PyGenericType, PyType> substitutions) {
    return match(expected, actual, context, substitutions, true);
  }

  private static boolean match(@Nullable PyType expected, @Nullable PyType actual, @NotNull TypeEvalContext context,
                               @Nullable Map<PyGenericType, PyType> substitutions, boolean recursive) {
    // TODO: subscriptable types?, module types?, etc.
    final PyClassType expectedClassType = as(expected, PyClassType.class);
    final PyClassType actualClassType = as(actual, PyClassType.class);
    
    // Special cases: object and type
    if (expectedClassType != null && ArrayUtil.contains(expectedClassType.getName(), PyNames.OBJECT, PyNames.TYPE)) {
      final PyBuiltinCache builtinCache = PyBuiltinCache.getInstance(expectedClassType.getPyClass());
      if (expectedClassType.equals(builtinCache.getObjectType())) {
        return true;
      }
      if (expectedClassType.equals(builtinCache.getTypeType()) &&
          actual instanceof PyInstantiableType && ((PyInstantiableType)actual).isDefinition()) {
        return true;
      }
    }
    if (expected instanceof PyInstantiableType && actual instanceof PyInstantiableType
        && !(expected instanceof PyGenericType && typeVarAcceptsBothClassAndInstanceTypes((PyGenericType)expected)) 
        && ((PyInstantiableType)expected).isDefinition() ^ ((PyInstantiableType)actual).isDefinition()) {
      return false;
    }
    if (actualClassType != null && PyNames.BASESTRING.equals(actualClassType.getName())) {
      final PyBuiltinCache builtinCache = PyBuiltinCache.getInstance(actualClassType.getPyClass());
      if (actualClassType.equals(builtinCache.getObjectType(PyNames.BASESTRING))) {
        return match(expected, builtinCache.getStrOrUnicodeType(), context, substitutions, recursive);
      }
    }
    if (expected instanceof PyGenericType && substitutions != null) {
      final PyGenericType generic = (PyGenericType)expected;
      final PyType subst = substitutions.get(generic);
      PyType bound = generic.getBound();
      // Promote int in Type[TypeVar('T', int)] to Type[int] before checking that bounds match
      if (generic.isDefinition() && bound instanceof PyInstantiableType) {
        bound = ((PyInstantiableType)bound).toClass();
      }
      if (!match(bound, actual, context, substitutions, recursive)) {
        return false;
      }
      else if (subst != null) {
        if (expected.equals(actual)) {
          return true;
        }
        else if (recursive) {
          return match(subst, actual, context, substitutions, false);
        }
        else {
          return false;
        }
      }
      else if (actual != null) {
        substitutions.put(generic, actual);
      }
      else if (bound != null) {
        substitutions.put(generic, bound);
      }
      return true;
    }
    if (expected == null || actual == null) {
      return true;
    }
    if (isUnknown(actual)) {
      return true;
    }
    if (actual instanceof PyUnionType) {
      final PyUnionType actualUnionType = (PyUnionType)actual;

      if (expected instanceof PyTupleType) {
        final PyTupleType expectedTupleType = (PyTupleType)expected;
        final int elementCount = expectedTupleType.getElementCount();

        if (!expectedTupleType.isHomogeneous() && consistsOfSameElementNumberTuples(actualUnionType, elementCount)) {
          return substituteExpectedElementsWithUnions(expectedTupleType, elementCount, actualUnionType, context, substitutions, recursive);
        }
      }

      for (PyType m : actualUnionType.getMembers()) {
        if (match(expected, m, context, substitutions, recursive)) {
          return true;
        }
      }
      return false;
    }
    if (expected instanceof PyUnionType) {
      final Collection<PyType> expectedUnionTypeMembers = ((PyUnionType)expected).getMembers();
      final StreamEx<PyType> notGenericTypes = StreamEx.of(expectedUnionTypeMembers).filter(type -> !PyGenericType.class.isInstance(type));
      final StreamEx<PyGenericType> genericTypes = StreamEx.of(expectedUnionTypeMembers).select(PyGenericType.class);

      for (PyType t : notGenericTypes.append(genericTypes)) {
        if (match(t, actual, context, substitutions, recursive)) {
          return true;
        }
      }
      return false;
    }
    if (expectedClassType != null && actualClassType != null) {
      final PyClass superClass = expectedClassType.getPyClass();
      final PyClass subClass = actualClassType.getPyClass();
      if (expected instanceof PyTupleType && actual instanceof PyTupleType) {
        final PyTupleType superTupleType = (PyTupleType)expected;
        final PyTupleType subTupleType = (PyTupleType)actual;
        if (!superTupleType.isHomogeneous() && !subTupleType.isHomogeneous()) {
          if (superTupleType.getElementCount() != subTupleType.getElementCount()) {
            return false;
          }
          else {
            for (int i = 0; i < superTupleType.getElementCount(); i++) {
              if (!match(superTupleType.getElementType(i), subTupleType.getElementType(i), context, substitutions, recursive)) {
                return false;
              }
            }
            return true;
          }
        }
        else if (superTupleType.isHomogeneous() && !subTupleType.isHomogeneous()) {
          final PyType expectedElementType = superTupleType.getIteratedItemType();
          for (int i = 0; i < subTupleType.getElementCount(); i++) {
            if (!match(expectedElementType, subTupleType.getElementType(i), context, substitutions, recursive)) {
              return false;
            }
          }
          return true;
        }
        else if (!superTupleType.isHomogeneous() && subTupleType.isHomogeneous()) {
          return false;
        }
        else {
          return match(superTupleType.getIteratedItemType(), subTupleType.getIteratedItemType(), context, substitutions, recursive);
        }
      }
      else if (expected instanceof PyCollectionType && actual instanceof PyTupleType) {
        if (!matchClasses(superClass, subClass, context)) {
          return false;
        }

        final PyType superElementType = ((PyCollectionType)expected).getIteratedItemType();
        final PyType subElementType = ((PyTupleType)actual).getIteratedItemType();

        if (!match(superElementType, subElementType, context, substitutions, recursive)) {
          return false;
        }

        return true;
      }
      else if (expected instanceof PyCollectionType) {
        if (!matchClasses(superClass, subClass, context)) {
          return false;
        }
        // TODO: Match generic parameters based on the correspondence between the generic parameters of subClass and its base classes
        final List<PyType> superElementTypes = ((PyCollectionType)expected).getElementTypes(context);
        final PyCollectionType actualCollectionType = as(actual, PyCollectionType.class);
        final List<PyType> subElementTypes = actualCollectionType != null ?
                                             actualCollectionType.getElementTypes(context) :
                                             Collections.emptyList();
        for (int i = 0; i < superElementTypes.size(); i++) {
          final PyType subElementType = i < subElementTypes.size() ? subElementTypes.get(i) : null;
          if (!match(superElementTypes.get(i), subElementType, context, substitutions, recursive)) {
            return false;
          }
        }
        return true;
      }

      else if (matchClasses(superClass, subClass, context)) {
        return true;
      }
      else if (actualClassType.isDefinition() && PyNames.CALLABLE.equals(expected.getName())) {
        return true;
      }
      if (expected.equals(actual)) {
        return true;
      }
    }
    if (actual instanceof PyFunctionTypeImpl && expectedClassType != null) {
      final PyClass superClass = expectedClassType.getPyClass();
      if (PyNames.CALLABLE.equals(superClass.getName())) {
        return true;
      }
    }
    if (actual instanceof PyStructuralType && ((PyStructuralType)actual).isInferredFromUsages()) {
      return true;
    }
    if (expected instanceof PyStructuralType && actual instanceof PyStructuralType) {
      final PyStructuralType expectedStructural = (PyStructuralType)expected;
      final PyStructuralType actualStructural = (PyStructuralType)actual;
      if (expectedStructural.isInferredFromUsages()) {
        return true;
      }
      return expectedStructural.getAttributeNames().containsAll(actualStructural.getAttributeNames());
    }
    if (expected instanceof PyStructuralType && actualClassType != null) {
      if (overridesGetAttr(actualClassType.getPyClass(), context)) {
        return true;
      }
      final Set<String> actualAttributes = actualClassType.getMemberNames(true, context);
      return actualAttributes.containsAll(((PyStructuralType)expected).getAttributeNames());
    }
    if (actual instanceof PyStructuralType && expectedClassType != null) {
      final Set<String> expectedAttributes = expectedClassType.getMemberNames(true, context);
      return expectedAttributes.containsAll(((PyStructuralType)actual).getAttributeNames());
    }
    if (actual instanceof PyCallableType && expected instanceof PyCallableType) {
      final PyCallableType expectedCallable = (PyCallableType)expected;
      final PyCallableType actualCallable = (PyCallableType)actual;
      if (expectedCallable.isCallable() && actualCallable.isCallable()) {
        final List<PyCallableParameter> expectedParameters = expectedCallable.getParameters(context);
        final List<PyCallableParameter> actualParameters = actualCallable.getParameters(context);
        if (expectedParameters != null && actualParameters != null) {
          final int size = Math.min(expectedParameters.size(), actualParameters.size());
          for (int i = 0; i < size; i++) {
            final PyCallableParameter expectedParam = expectedParameters.get(i);
            final PyCallableParameter actualParam = actualParameters.get(i);
            // TODO: Check named and star params, not only positional ones
            if (!match(expectedParam.getType(context), actualParam.getType(context), context, substitutions, recursive)) {
              return false;
            }
          }
        }
        if (!match(expectedCallable.getReturnType(context), actualCallable.getReturnType(context), context, substitutions, recursive)) {
          return false;
        }
        return true;
      }
    }
    return matchNumericTypes(expected, actual);
  }

  private static boolean typeVarAcceptsBothClassAndInstanceTypes(@NotNull PyGenericType typeVar) {
    return !typeVar.isDefinition() && typeVar.getBound() == null;
  }

  private static boolean consistsOfSameElementNumberTuples(@NotNull PyUnionType unionType, int elementCount) {
    for (PyType type : unionType.getMembers()) {
      if (type instanceof PyTupleType) {
        final PyTupleType tupleType = (PyTupleType)type;

        if (!tupleType.isHomogeneous() && elementCount != tupleType.getElementCount()) {
          return false;
        }
      }
      else {
        return false;
      }
    }

    return true;
  }

  private static boolean substituteExpectedElementsWithUnions(@NotNull PyTupleType expected,
                                                              int elementCount,
                                                              @NotNull PyUnionType actual,
                                                              @NotNull TypeEvalContext context,
                                                              @Nullable Map<PyGenericType, PyType> substitutions,
                                                              boolean recursive) {
    for (int i = 0; i < elementCount; i++) {
      final int currentIndex = i;

      final PyType elementType = PyUnionType.union(
        StreamEx
          .of(actual.getMembers())
          .select(PyTupleType.class)
          .map(type -> type.getElementType(currentIndex))
          .toList()
      );

      if (!match(expected.getElementType(i), elementType, context, substitutions, recursive)) {
        return false;
      }
    }

    return true;
  }

  private static boolean matchNumericTypes(PyType expected, PyType actual) {
    final String superName = expected.getName();
    final String subName = actual.getName();
    final boolean subIsBool = "bool".equals(subName);
    final boolean subIsInt = "int".equals(subName);
    final boolean subIsLong = "long".equals(subName);
    final boolean subIsFloat = "float".equals(subName);
    final boolean subIsComplex = "complex".equals(subName);
    if (superName == null || subName == null ||
        superName.equals(subName) ||
        ("int".equals(superName) && subIsBool) ||
        (("long".equals(superName) || PyNames.ABC_INTEGRAL.equals(superName)) && (subIsBool || subIsInt)) ||
        (("float".equals(superName) || PyNames.ABC_REAL.equals(superName)) && (subIsBool || subIsInt || subIsLong)) ||
        (("complex".equals(superName) || PyNames.ABC_COMPLEX.equals(superName)) && (subIsBool || subIsInt || subIsLong || subIsFloat)) ||
        (PyNames.ABC_NUMBER.equals(superName) && (subIsBool || subIsInt || subIsLong || subIsFloat || subIsComplex))) {
      return true;
    }
    return false;
  }

  public static boolean isUnknown(@Nullable PyType type) {
    return isUnknown(type, true);
  }

  public static boolean isUnknown(@Nullable PyType type, boolean genericsAreUnknown) {
    if (type == null || (genericsAreUnknown && type instanceof PyGenericType)) {
      return true;
    }
    if (type instanceof PyUnionType) {
      final PyUnionType union = (PyUnionType)type;
      for (PyType t : union.getMembers()) {
        if (isUnknown(t, genericsAreUnknown)) {
          return true;
        }
      }
    }
    return false;
  }

  @Nullable
  public static PyType toNonWeakType(@Nullable PyType type, @NotNull TypeEvalContext context) {
    if (type instanceof PyUnionType) {
      final PyUnionType unionType = (PyUnionType)type;
      if (unionType.isWeak()) {
        return unionType.excludeNull(context);
      }
    }
    return type;
  }

  public static boolean hasGenerics(@Nullable PyType type, @NotNull TypeEvalContext context) {
    final Set<PyGenericType> collected = new HashSet<>();
    collectGenerics(type, context, collected, new HashSet<>());
    return !collected.isEmpty();
  }

  private static void collectGenerics(@Nullable PyType type, @NotNull TypeEvalContext context, @NotNull Set<PyGenericType> collected,
                                      @NotNull Set<PyType> visited) {
    if (visited.contains(type)) {
      return;
    }
    visited.add(type);
    if (type instanceof PyGenericType) {
      collected.add((PyGenericType)type);
    }
    else if (type instanceof PyUnionType) {
      final PyUnionType union = (PyUnionType)type;
      for (PyType t : union.getMembers()) {
        collectGenerics(t, context, collected, visited);
      }
    }
    else if (type instanceof PyTupleType) {
      final PyTupleType tuple = (PyTupleType)type;
      final int n = tuple.isHomogeneous() ? 1 : tuple.getElementCount();
      for (int i = 0; i < n; i++) {
        collectGenerics(tuple.getElementType(i), context, collected, visited);
      }
    }
    else if (type instanceof PyCollectionType) {
      final PyCollectionType collection = (PyCollectionType)type;
      for (PyType elementType : collection.getElementTypes(context)) {
        collectGenerics(elementType, context, collected, visited);
      }
    }
    else if (type instanceof PyCallableType) {
      final PyCallableType callable = (PyCallableType)type;
      final List<PyCallableParameter> parameters = callable.getParameters(context);
      if (parameters != null) {
        for (PyCallableParameter parameter : parameters) {
          if (parameter != null) {
            collectGenerics(parameter.getType(context), context, collected, visited);
          }
        }
      }
      collectGenerics(callable.getReturnType(context), context, collected, visited);
    }
  }

  @Nullable
  public static PyType substitute(@Nullable PyType type, @NotNull Map<PyGenericType, PyType> substitutions,
                                  @NotNull TypeEvalContext context) {
    if (hasGenerics(type, context)) {
      if (type instanceof PyGenericType) {
        final PyGenericType typeVar = (PyGenericType)type;
        PyType substitution = substitutions.get(typeVar);
        if (substitution == null) {
          if (!typeVar.isDefinition()) {
            final PyInstantiableType<?> classType = as(substitutions.get(typeVar.toClass()), PyInstantiableType.class);
            if (classType != null) {
              substitution = classType.toInstance();
            }
          }
          else {
            final PyInstantiableType<?> instanceType = as(substitutions.get(typeVar.toInstance()), PyInstantiableType.class);
            if (instanceType != null) {
              substitution = instanceType.toClass();
            }
          }
        }
        if (substitution instanceof PyGenericType && substitution != type) {
          final PyType recursive = substitute(substitution, substitutions, context);
          if (recursive != null) {
            return recursive;
          }
        }
        return substitution;
      }
      else if (type instanceof PyUnionType) {
        final PyUnionType union = (PyUnionType)type;
        final List<PyType> results = new ArrayList<>();
        for (PyType t : union.getMembers()) {
          final PyType subst = substitute(t, substitutions, context);
          results.add(subst);
        }
        return PyUnionType.union(results);
      }
      else if (type instanceof PyCollectionTypeImpl) {
        final PyCollectionTypeImpl collection = (PyCollectionTypeImpl)type;
        final List<PyType> elementTypes = collection.getElementTypes(context);
        final List<PyType> substitutes = new ArrayList<>();
        for (PyType elementType : elementTypes) {
          substitutes.add(substitute(elementType, substitutions, context));
        }
        return new PyCollectionTypeImpl(collection.getPyClass(), collection.isDefinition(), substitutes);
      }
      else if (type instanceof PyTupleType) {
        final PyTupleType tupleType = (PyTupleType)type;
        final PyClass tupleClass = tupleType.getPyClass();

        final List<PyType> oldElementTypes = tupleType.isHomogeneous()
                                             ? Collections.singletonList(tupleType.getIteratedItemType())
                                             : tupleType.getElementTypes(context);

        final List<PyType> newElementTypes =
          ContainerUtil.map(oldElementTypes, elementType -> substitute(elementType, substitutions, context));

        return new PyTupleType(tupleClass, newElementTypes, tupleType.isHomogeneous());
      }
      else if (type instanceof PyCallableType) {
        final PyCallableType callable = (PyCallableType)type;
        List<PyCallableParameter> substParams = null;
        final List<PyCallableParameter> parameters = callable.getParameters(context);
        if (parameters != null) {
          substParams = new ArrayList<>();
          for (PyCallableParameter parameter : parameters) {
            final PyType substType = substitute(parameter.getType(context), substitutions, context);
            final PyCallableParameter subst = parameter.getParameter() != null ?
                                              new PyCallableParameterImpl(parameter.getParameter()) :
                                              new PyCallableParameterImpl(parameter.getName(), substType);
            substParams.add(subst);
          }
        }
        final PyType substResult = substitute(callable.getReturnType(context), substitutions, context);
        return new PyCallableTypeImpl(substParams, substResult);
      }
    }
    return type;
  }

  @Nullable
  public static Map<PyGenericType, PyType> unifyGenericCall(@Nullable PyExpression receiver,
                                                            @NotNull Map<PyExpression, PyNamedParameter> arguments,
                                                            @NotNull TypeEvalContext context) {
    final Map<PyGenericType, PyType> substitutions = unifyReceiver(receiver, context);

    PyNamedParameter positionalParameter = null;
    final List<PyType> positionalTypes = new ArrayList<>();

    PyNamedParameter keywordParameter = null;
    final List<PyType> keywordTypes = new ArrayList<>();

    for (Map.Entry<PyExpression, PyNamedParameter> entry : arguments.entrySet()) {
      final PyNamedParameter parameter = entry.getValue();
      final PyType actualArgType = context.getType(entry.getKey());

      if (parameter.isPositionalContainer()) {
        if (positionalParameter == null) positionalParameter = parameter;
        positionalTypes.add(actualArgType);
      }
      else if (parameter.isKeywordContainer()) {
        if (keywordParameter == null) keywordParameter = parameter;
        keywordTypes.add(actualArgType);
      }
      else if (!match(parameter.getArgumentType(context), actualArgType, context, substitutions)) {
        return null;
      }
    }

    if (positionalParameter != null &&
        !match(positionalParameter.getArgumentType(context), PyUnionType.union(positionalTypes), context, substitutions)) {
      return null;
    }

    if (keywordParameter != null &&
        !match(keywordParameter.getArgumentType(context), PyUnionType.union(keywordTypes), context, substitutions)) {
      return null;
    }

    return substitutions;
  }

  @NotNull
  public static Map<PyGenericType, PyType> unifyReceiver(@Nullable PyExpression receiver, @NotNull TypeEvalContext context) {
    final Map<PyGenericType, PyType> substitutions = new LinkedHashMap<>();
    // Collect generic params of object type
    final Set<PyGenericType> generics = new LinkedHashSet<>();
    final PyType qualifierType = receiver != null ? context.getType(receiver) : null;
    collectGenerics(qualifierType, context, generics, new HashSet<>());
    for (PyGenericType t : generics) {
      substitutions.put(t, t);
    }
    if (qualifierType != null) {
      for (PyClassType type : toPossibleClassTypes(qualifierType)) {
        for (PyTypeProvider provider : Extensions.getExtensions(PyTypeProvider.EP_NAME)) {
          final PyType genericType = provider.getGenericType(type.getPyClass(), context);
          if (genericType != null) {
            match(genericType, qualifierType, context, substitutions);
          }
          for (Map.Entry<PyType, PyType> entry : provider.getGenericSubstitutions(type.getPyClass(), context).entrySet()) {
            final PyGenericType genericKey = as(entry.getKey(), PyGenericType.class);
            final PyType value = entry.getValue();
            if (genericKey != null && value != null && !substitutions.containsKey(genericKey)) {
              substitutions.put(genericKey, value);
            }
          }
        }
      }
    }
    return substitutions;
  }

  @NotNull
  private static List<PyClassType> toPossibleClassTypes(@NotNull PyType type) {
    final PyClassType classType = as(type, PyClassType.class);
    if (classType != null) {
      return Collections.singletonList(classType);
    }
    final PyUnionType unionType = as(type, PyUnionType.class);
    if (unionType != null) {
      return StreamEx.of(unionType.getMembers()).nonNull().flatMap(t -> toPossibleClassTypes(t).stream()).toList();
    }
    return Collections.emptyList();
  }

  private static boolean matchClasses(@Nullable PyClass superClass, @Nullable PyClass subClass, @NotNull TypeEvalContext context) {
    if (superClass == null ||
        subClass == null ||
        subClass.isSubclass(superClass, context) ||
        PyABCUtil.isSubclass(subClass, superClass, context) ||
        isStrUnicodeMatch(subClass, superClass) ||
        PyUtil.hasUnresolvedAncestors(subClass, context)) {
      return true;
    }
    else {
      final String superName = superClass.getName();
      return superName != null && superName.equals(subClass.getName());
    }
  }

  private static boolean isStrUnicodeMatch(@NotNull PyClass subClass, @NotNull PyClass superClass) {
    // TODO: Check for subclasses as well
    return PyNames.TYPE_STR.equals(subClass.getName()) && PyNames.TYPE_UNICODE.equals(superClass.getName());
  }

  @NotNull
  public static List<AnalyzeCallResults> analyzeCallSite(@NotNull PyCallSiteExpression callSite, @NotNull TypeEvalContext context) {
    final List<AnalyzeCallResults> results = new ArrayList<>();
    for (PyCallable callable : multiResolveCallee(callSite, context)) {
      final PyExpression receiver = getReceiver(callSite, callable);
      final PyCallExpressionHelper.ArgumentMappingResults mapping = PyCallExpressionHelper.mapArguments(callSite, callable, context);
      results.add(new AnalyzeCallResults(callable, receiver, mapping));
    }
    return results;
  }

  @NotNull
  private static List<PyCallable> multiResolveCallee(@NotNull PyCallSiteExpression callSite, @NotNull TypeEvalContext context) {
    final PyResolveContext resolveContext = PyResolveContext.noImplicits().withTypeEvalContext(context);
    if (callSite instanceof PyCallExpression) {
      final List<PyCallExpression.PyRatedCallee> ratedCallees = ((PyCallExpression)callSite).multiResolveRatedCalleeFunction(resolveContext);
      return ContainerUtil.map(PyUtil.filterTopPriorityResults(ratedCallees), PyCallExpression.PyRatedCallee::getElement);
    }
    else if (callSite instanceof PySubscriptionExpression || callSite instanceof PyBinaryExpression) {
      final List<PyCallable> results = new ArrayList<>();
      boolean resolvedToUnknownResult = false;
      for (PsiElement result : PyUtil.multiResolveTopPriority(callSite, resolveContext)) {
        if (result instanceof PyCallable) {
          results.add((PyCallable)result);
          continue;
        }
        if (result instanceof PyTypedElement) {
          final PyType resultType = context.getType((PyTypedElement)result);
          if (resultType instanceof PyFunctionType) {
            results.add(((PyFunctionType)resultType).getCallable());
            continue;
          }
        }
        resolvedToUnknownResult = true;
      }
      return resolvedToUnknownResult ? Collections.emptyList() : results;
    }
    else {
      return Collections.emptyList();
    }
  }

  @Nullable
  public static Boolean isCallable(@Nullable PyType type) {
    if (type == null) {
      return null;
    }
    if (type instanceof PyUnionType) {
      return isUnionCallable((PyUnionType)type);
    }
    if (type instanceof PyCallableType) {
      return ((PyCallableType)type).isCallable();
    }
    if (type instanceof PyStructuralType && ((PyStructuralType)type).isInferredFromUsages()) {
      return true;
    }
    return false;
  }

  /**
   * If at least one is callable -- it is callable.
   * If at least one is unknown -- it is unknown.
   * It is false otherwise.
   */
  @Nullable
  private static Boolean isUnionCallable(@NotNull final PyUnionType type) {
    for (final PyType member : type.getMembers()) {
      final Boolean callable = isCallable(member);
      if (callable == null) {
        return null;
      }
      if (callable) {
        return true;
      }
    }
    return false;
  }

  public static boolean overridesGetAttr(@NotNull PyClass cls, @NotNull TypeEvalContext context) {
    PsiElement method = resolveClassMember(cls, PyNames.GETATTR, context);
    if (method != null) {
      return true;
    }
    method = resolveClassMember(cls, PyNames.GETATTRIBUTE, context);
    if (method != null && !PyBuiltinCache.getInstance(cls).isBuiltin(method)) {
      return true;
    }
    return false;
  }

  @Nullable
  private static PsiElement resolveClassMember(@NotNull PyClass cls, @NotNull String name, @NotNull TypeEvalContext context) {
    final PyType type = context.getType(cls);
    if (type != null) {
      final PyResolveContext resolveContext = PyResolveContext.noImplicits().withTypeEvalContext(context);
      final List<? extends RatedResolveResult> results = type.resolveMember(name, null, AccessDirection.READ, resolveContext);
      if (results != null && !results.isEmpty()) {
        return results.get(0).getElement();
      }
    }
    return null;
  }

  @Nullable
  public static PyType getTargetTypeFromTupleAssignment(@NotNull PyTargetExpression target, @NotNull PyTupleExpression parentTuple,
                                                        @NotNull PyTupleType assignedTupleType) {
    final int count = assignedTupleType.getElementCount();
    final PyExpression[] elements = parentTuple.getElements();
    if (elements.length == count || assignedTupleType.isHomogeneous()) {
      final int index = ArrayUtil.indexOf(elements, target);
      if (index >= 0) {
        return assignedTupleType.getElementType(index);
      }
      for (int i = 0; i < count; i++) {
        PyExpression element = elements[i];
        while (element instanceof PyParenthesizedExpression) {
          element = ((PyParenthesizedExpression)element).getContainedExpression();
        }
        if (element instanceof PyTupleExpression) {
          final PyType elementType = assignedTupleType.getElementType(i);
          if (elementType instanceof PyTupleType) {
            final PyType result = getTargetTypeFromTupleAssignment(target, (PyTupleExpression)element, (PyTupleType)elementType);
            if (result != null) {
              return result;
            }
          }
        }
      }
    }
    return null;
  }

  @NotNull
  public static List<PyParameter> filterExplicitParameters(@NotNull List<PyParameter> parameters, @NotNull PyCallable callable,
                                                           @NotNull PyCallSiteExpression callSite,
                                                           @NotNull PyResolveContext resolveContext) {
    final int implicitOffset;
    if (callSite instanceof PyCallExpression) {
      final PyCallExpression callExpr = (PyCallExpression)callSite;
      final PyExpression callee = callExpr.getCallee();
      if (callee instanceof PyReferenceExpression && callable instanceof PyFunction) {
        implicitOffset = PyCallExpressionHelper.getImplicitArgumentCount((PyReferenceExpression)callee, (PyFunction)callable,
                                                                         resolveContext);
      }
      else {
        implicitOffset = 0;
      }
    }
    else if (callSite instanceof PySubscriptionExpression || callSite instanceof PyBinaryExpression) {
      implicitOffset = 1;
    }
    else {
      implicitOffset = 0;
    }
    return parameters.subList(Math.min(implicitOffset, parameters.size()), parameters.size());
  }

  @NotNull
  public static List<PyExpression> getArguments(@NotNull PyCallSiteExpression expr, @NotNull PsiElement resolved) {
    if (expr instanceof PyCallExpression) {
      return Arrays.asList(((PyCallExpression)expr).getArguments());
    }
    else if (expr instanceof PySubscriptionExpression) {
      return Collections.singletonList(((PySubscriptionExpression)expr).getIndexExpression());
    }
    else if (expr instanceof PyBinaryExpression) {
      final PyBinaryExpression binaryExpr = (PyBinaryExpression)expr;
      final boolean isRight = resolved instanceof PsiNamedElement && PyNames.isRightOperatorName(((PsiNamedElement)resolved).getName());
      return Collections.singletonList(isRight ? binaryExpr.getLeftExpression() : binaryExpr.getRightExpression());
    }
    else {
      return Collections.emptyList();
    }
  }

  @Nullable
  public static PyExpression getReceiver(@NotNull PyCallSiteExpression expr, @NotNull PsiElement resolved) {
    if (expr instanceof PyCallExpression) {
      if (resolved instanceof PyFunction) {
        final PyFunction function = (PyFunction)resolved;
        if (function.getModifier() == PyFunction.Modifier.STATICMETHOD) {
          return null;
        }
      }
      final PyExpression callee = ((PyCallExpression)expr).getCallee();
      return callee instanceof PyQualifiedExpression ? ((PyQualifiedExpression)callee).getQualifier() : null;
    }
    else if (expr instanceof PySubscriptionExpression) {
      return ((PySubscriptionExpression)expr).getOperand();
    }
    else if (expr instanceof PyBinaryExpression) {
      final PyBinaryExpression binaryExpr = (PyBinaryExpression)expr;
      final boolean isRight = resolved instanceof PsiNamedElement && PyNames.isRightOperatorName(((PsiNamedElement)resolved).getName());
      return isRight ? binaryExpr.getRightExpression() : binaryExpr.getLeftExpression();
    }
    else {
      return null;
    }
  }

  public static class AnalyzeCallResults {
    @NotNull private final PyCallable myCallable;
    @Nullable private final PyExpression myReceiver;
    @NotNull private final PyCallExpressionHelper.ArgumentMappingResults myMapping;

    public AnalyzeCallResults(@NotNull PyCallable callable, @Nullable PyExpression receiver,
                              @NotNull PyCallExpressionHelper.ArgumentMappingResults mapping) {
      myCallable = callable;
      myReceiver = receiver;
      myMapping = mapping;
    }

    @NotNull
    public PyCallable getCallable() {
      return myCallable;
    }

    @Nullable
    public PyExpression getReceiver() {
      return myReceiver;
    }

    @NotNull
    public PyCallExpressionHelper.ArgumentMappingResults getMapping() {
      return myMapping;
    }
  }
}

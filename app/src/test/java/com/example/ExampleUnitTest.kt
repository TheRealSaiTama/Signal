package com.example

import org.junit.Test
import java.lang.reflect.Modifier

class ExampleUnitTest {
  @Test
  fun inspectDeepFilterNet() {
    try {
      val clazz = Class.forName("com.rikorose.deepfilternet.NativeDeepFilterNet", false, ExampleUnitTest::class.java.classLoader)
      println("=== CLASS: ${clazz.name} ===")
      println("--- CONSTRUCTORS ---")
      for (ctor in clazz.declaredConstructors) {
        println(ctor.toString())
      }
      println("--- METHODS ---")
      for (method in clazz.declaredMethods) {
        val modifiers = Modifier.toString(method.modifiers)
        println("$modifiers ${method.returnType.name} ${method.name}(${method.parameterTypes.joinToString { it.name }})")
        if (method.name == "onModelLoaded") {
          println("  -> Generic: ${method.genericParameterTypes.joinToString()}")
        }
      }
    } catch (e: Exception) {
      e.printStackTrace()
    }
  }
}


package io.ino.solrs

import org.mockito.Mockito.{mock => mockitoMock}

import scala.reflect.runtime.universe._

/**
 * Improves over MockitoSugar in that it supports higher kinded types.
 * MockitoSugar fails for them with "erroneous or inaccessible type" due to the used
 * Manifest (see also http://stackoverflow.com/questions/26010800/why-does-getting-a-manifest-of-a-trait-with-a-higher-kinded-type-does-not-work)
 */
trait NewMockitoSugar {

  def mock[T <: AnyRef](implicit tag: TypeTag[T]): T = {
    val m = tag.mirror
    mockitoMock(m.runtimeClass(tag.tpe).asInstanceOf[Class[T]])
  }

}
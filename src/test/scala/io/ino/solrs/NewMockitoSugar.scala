package io.ino.solrs

import izumi.reflect.Tag
import org.mockito.Mockito.{mock => mockitoMock}

/**
 * Improves over MockitoSugar in that it supports higher kinded types.
 * MockitoSugar fails for them with "erroneous or inaccessible type" due to the used
 * Manifest (see also http://stackoverflow.com/questions/26010800/why-does-getting-a-manifest-of-a-trait-with-a-higher-kinded-type-does-not-work)
 */
trait NewMockitoSugar {

  def mock[T <: AnyRef](implicit tag: Tag[T]): T = {
    mockitoMock(tag.closestClass.asInstanceOf[Class[T]])
  }

}
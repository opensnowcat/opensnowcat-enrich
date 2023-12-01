/*
 * Copyright (c) 2020-2022 Snowplow Analytics Ltd. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */

package io.circe.jackson.enrich

import com.fasterxml.jackson.core.Version
import com.fasterxml.jackson.databind.Module.SetupContext
import com.fasterxml.jackson.databind._
import com.fasterxml.jackson.databind.deser.Deserializers
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.databind.ser.Serializers
import io.circe.Json
import io.circe.jackson.CirceJsonSerializer

object CirceJsonModule extends SimpleModule("SPCirceJson", Version.unknownVersion()) {
  override final def setupModule(context: SetupContext): Unit = {
    context.addDeserializers(
      new Deserializers.Base {
        override final def findBeanDeserializer(
          javaType: JavaType,
          config: DeserializationConfig,
          beanDesc: BeanDescription
        ): CirceJsonDeserializer = {
          val klass = javaType.getRawClass
          if (classOf[Json].isAssignableFrom(klass) || klass == Json.JNull.getClass)
            new CirceJsonDeserializer(klass)
          else null
        }
      }
    )

    context.addSerializers(
      new Serializers.Base {
        override final def findSerializer(
          config: SerializationConfig,
          javaType: JavaType,
          beanDesc: BeanDescription
        ): JsonSerializer[Object] = {
          val ser: Object =
            if (classOf[Json].isAssignableFrom(beanDesc.getBeanClass))
              CirceJsonSerializer
            else null

          ser.asInstanceOf[JsonSerializer[Object]]
        }
      }
    )
  }
}

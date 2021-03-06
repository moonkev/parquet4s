package com.github.mjakubowski84.parquet4s

import org.apache.parquet.schema.Type.Repetition
import org.apache.parquet.schema._
import shapeless._
import shapeless.labelled._

import scala.language.higherKinds

/**
  * Type class that allows to build schema of Parquet file out from regular Scala type, typically case class.
  * @tparam T scala type that represents schema of Parquet data.
  */
trait ParquetSchemaResolver[T] {

  /**
    * @return list of [[org.apache.parquet.schema.Type]] for each product element that <i>T</i> contains.
    */
  def resolveSchema: List[Type]

}

object ParquetSchemaResolver
  extends SchemaDefs {

  /**
    * Builds full Parquet file schema ([[org.apache.parquet.schema.MessageType]]) from <i>T</i>.
    */
  def resolveSchema[T](implicit g: ParquetSchemaResolver[T]): MessageType = Message(g.resolveSchema:_*)

  implicit val hnil: ParquetSchemaResolver[HNil] = new ParquetSchemaResolver[HNil] {
    def resolveSchema: List[Type] = List.empty
  }

  implicit def hcons[K <: Symbol, V, T <: HList](implicit
                                                 witness: Witness.Aux[K],
                                                 schemaDef: TypedSchemaDef[V],
                                                 rest: ParquetSchemaResolver[T]
                                                ): ParquetSchemaResolver[FieldType[K, V] :: T] =
    new ParquetSchemaResolver[FieldType[K, V] :: T] {
      def resolveSchema: List[Type] = schemaDef(witness.value.name) +: rest.resolveSchema
    }

  implicit def generic[T, G](implicit
                             lg: LabelledGeneric.Aux[T, G],
                             rest: ParquetSchemaResolver[G]
                              ): ParquetSchemaResolver[T] = new ParquetSchemaResolver[T] {
    def resolveSchema: List[Type] = rest.resolveSchema
  }
}

object Message {

  val name = "parquet4s-schema"

  def apply(fields: Type*): MessageType = Types.buildMessage().addFields(fields:_*).named(name)

}

trait SchemaDef {

  type Self <: SchemaDef

  def apply(name: String): Type

  def withRequired(required: Boolean): Self

}

case class PrimitiveSchemaDef(
                             primitiveType: PrimitiveType.PrimitiveTypeName,
                             required: Boolean = true,
                             originalType: Option[OriginalType] = None
                             ) extends SchemaDef {

  override type Self = PrimitiveSchemaDef

  override def apply(name: String): Type = {
    val builder = Types.primitive(
      primitiveType,
      if (required) Repetition.REQUIRED else Repetition.OPTIONAL
    )
    originalType.foldLeft(builder)(_.as(_)).named(name)
  }

  override def withRequired(required: Boolean): PrimitiveSchemaDef = this.copy(required = required)
}

object GroupSchemaDef {

  def required(fields: Type*): GroupSchemaDef = GroupSchemaDef(fields, required = true)
  def optional(fields: Type*): GroupSchemaDef = GroupSchemaDef(fields, required = false)

}

case class GroupSchemaDef(fields: Seq[Type], required: Boolean) extends SchemaDef {

  override type Self = GroupSchemaDef

  override def apply(name: String): Type = {
    val builder = if (required) Types.requiredGroup() else Types.optionalGroup()
    builder.addFields(fields:_*).named(name)
  }

  override def withRequired(required: Boolean): GroupSchemaDef = this.copy(required = required)
}

object ListGroupSchemaDef {

  val ElementName = "element"

  def required(elementSchemaDef: SchemaDef): ListGroupSchemaDef = ListGroupSchemaDef(elementSchemaDef(ElementName), required = true)
  def optional(elementSchemaDef: SchemaDef): ListGroupSchemaDef = ListGroupSchemaDef(elementSchemaDef(ElementName), required = false)

}

case class ListGroupSchemaDef(element: Type, required: Boolean) extends SchemaDef {
  override def apply(name: String): Type = {
    val builder = if (required) Types.requiredList() else Types.optionalList()
    builder.element(element).named(name)
  }

  override type Self = ListGroupSchemaDef

  override def withRequired(required: Boolean): ListGroupSchemaDef = this.copy(required = required)
}

object MapSchemaDef {

  val KeyName = "key"
  val ValueName = "value"

  def required(keySchemaDef: SchemaDef, valueSchemaDef: SchemaDef): MapSchemaDef = new MapSchemaDef(
    keySchemaDef(KeyName), valueSchemaDef(ValueName), required = true
  )

  def optional(keySchemaDef: SchemaDef, valueSchemaDef: SchemaDef): MapSchemaDef = new MapSchemaDef(
    keySchemaDef(KeyName), valueSchemaDef(ValueName), required = false
  )

}

case class MapSchemaDef(key: Type, value: Type, required: Boolean) extends SchemaDef {
  override def apply(name: String): Type = {
    val builder = if (required) Types.requiredMap() else Types.optionalMap()
    builder.key(key).value(value).named(name)
  }

  override type Self = MapSchemaDef

  override def withRequired(required: Boolean): MapSchemaDef = this.copy(required = required)
}

// TODO compare schemas here with those from Spark (for example are really primitives required by default, but strings not? maybe it is about empty string? test it)
// TODO check how Spark saves null and empty primitive values
trait SchemaDefs {

  trait Tag[V]
  type TypedSchemaDef[V] = SchemaDef with Tag[V]

  def typedSchemaDef[V](schemaDef: SchemaDef): TypedSchemaDef[V] = schemaDef.asInstanceOf[TypedSchemaDef[V]]

  implicit val stringSchema: TypedSchemaDef[String] =
    typedSchemaDef[String](
      PrimitiveSchemaDef(PrimitiveType.PrimitiveTypeName.BINARY, required = false, originalType = Some(OriginalType.UTF8))
    )

  implicit val intSchema: TypedSchemaDef[Int] =
    typedSchemaDef[Int](
      PrimitiveSchemaDef(PrimitiveType.PrimitiveTypeName.INT32, originalType = Some(OriginalType.INT_32))
    )

  implicit val longSchema: TypedSchemaDef[Long] =
    typedSchemaDef[Long](
      PrimitiveSchemaDef(PrimitiveType.PrimitiveTypeName.INT64, originalType = Some(OriginalType.INT_64))
    )

  implicit val floatSchema: TypedSchemaDef[Float] =
    typedSchemaDef[Float](
      PrimitiveSchemaDef(PrimitiveType.PrimitiveTypeName.FLOAT)
    )

  implicit val doubleSchema: TypedSchemaDef[Double] =
    typedSchemaDef[Double](
      PrimitiveSchemaDef(PrimitiveType.PrimitiveTypeName.DOUBLE)
    )

  implicit val booleanSchema: TypedSchemaDef[Boolean] =
    typedSchemaDef[Boolean](
      PrimitiveSchemaDef(PrimitiveType.PrimitiveTypeName.BOOLEAN)
    )

  implicit val dateSchema: TypedSchemaDef[java.sql.Date] =
    typedSchemaDef[java.sql.Date](
      PrimitiveSchemaDef(PrimitiveType.PrimitiveTypeName.INT32, required = false, originalType = Some(OriginalType.DATE))
    )

  implicit val timestampSchema: TypedSchemaDef[java.sql.Timestamp] =
    typedSchemaDef[java.sql.Timestamp](
      PrimitiveSchemaDef(PrimitiveType.PrimitiveTypeName.INT96, required = false)
    )

  implicit def productSchema[T](implicit parquetSchemaResolver: ParquetSchemaResolver[T]): TypedSchemaDef[T] =
    typedSchemaDef[T](
      GroupSchemaDef.optional(parquetSchemaResolver.resolveSchema:_*)
    )

  implicit def optionSchema[T](implicit tSchemaDef: TypedSchemaDef[T]): TypedSchemaDef[Option[T]] =
    typedSchemaDef[Option[T]](
      tSchemaDef.withRequired(false)
    )

  implicit def collectionSchema[E, Col[_]](implicit elementSchema: TypedSchemaDef[E]): TypedSchemaDef[Col[E]] =
    typedSchemaDef[Col[E]](
      ListGroupSchemaDef.optional(elementSchema)
    )

  implicit def mapSchema[MapKey, MapValue](implicit
                                           keySchema: TypedSchemaDef[MapKey],
                                           valueSchema: TypedSchemaDef[MapValue]
                                          ): TypedSchemaDef[Map[MapKey, MapValue]] =
    typedSchemaDef[Map[MapKey, MapValue]] {
      // type of the map key must be required
      MapSchemaDef.optional(keySchema.withRequired(true), valueSchema)
    }

}

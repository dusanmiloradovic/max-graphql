# Introduction

GraphQL server for Maximo is an implementation of the GraphQL tailored for Maximo. It requires absolutely no programming, all you need to do is  define one or more schemas in GraphQL Schema Definition Language (SDL).
Each of the SDL files have to fullfil the following:

- The file is placed inside the __schema__ directory
- The file has the graphql extension (for example - _receipts.graphql_)
- Define the maximo types you want to expose inside the SDL file
- The GraphQL queries and mutations are defined in the __top-level__ _Query_ and _Mutation_ types

Once the GraphQL server starts, all the queries and mutations will be merged into one schema. It is important to verify that there are no duplicate types inside the schemas.

## Define the Maximo type in the SDL file

```graphql
type PO{
  id:ID
  ponum:String
  description:String
  status:String
  orderdate:String
}

type POLINE{
  id:ID
  ponum:String
  description:String
  polinenum:Int
  orderqty:Float
}
```

Above is the example for PO and POLINE types. The name of the object types and its attributes have to be the same as in the Maximo Database Configuration. By convention, the object types have to be in uppercase, and the attributes in the lower case. The types of the attributes map to one of the standard GraphQL scalar types: _ID_, _String_, _Int_, _Float_ and _Boolean_ (see: https://graphql.org/graphql-js/basic-types/). Note that there is no standard _Date_ time ih GraphQL, dates and times are mapped to the _String_ type. The id of the object is defined with the __id__ attribute of the _ID_ GraphQL type, and it maps to the internal object id in Maximo (_poid_ and _polineid_ for the above objects). You __have__ to name this attribute __id__ when you define the type.

## GraphQL Query type

Once we have the type, we can define our query in the __Query__ GraphQL type:

```graphql
type Query {
  po(fromRow:Int, numRows:Int,_handle:String,qbe:POQBE):[PO]
}
```

We cover the ___handle__ and __qbe__ concepts later in this chapter, ignore them for the time being.
Note that we put just the __po__ type in the __Query__. This is the rule you should follow in your own schemas - put just the top level types inside the __Query__ type. The name of the type inside the _Query_ type is the name of the application in Maximo. In the above example - the name of the type inside the _Query_ is the __po__ (the Maximo name for the Purchase Order application). The return type is the list of the __PO__ types we defined before.

### Attributes fromRow and numRows

The number of records in any typical Maximo application can have hundreds of thousand or even milions of rows, therefore we need to specify the rows we are fetching from Maximo. The attribute __fromRow__ defines the starting row in a set, and the __numRows__ the number of the rows being fetched

Example:

```graphql

```
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

Open your local GraphQL playground (http://localhost:4001), and run the following

```graphql
query {
  po(fromRow:0, numRows:2){
    ponum
    description
    id
  }
}
```

## Getting the data from Maximo relationship

So far, we have demonstraded just how to fetch the data from the top-level object, in our example the _PO_ type. Getting the relationship data is straightforward. 

Change the _PO_ type in your schema to following:

```graphql
type PO{
  id:ID
  ponum:String
  description:String
  status:String
  orderdate:String
  poline(fromRow:Int,numRows:Int, _handle:String, qbe:POLINEQBE):[POLINE]	
}
```

Note we added the __poline__ object type in the __PO__ tyoe. The __poline__ has the same signatyre as the __po__ type from within the __Query__ type (it has the _fromRow_, _numRows_, _handle_ and _qbe_ arguments).
The name of the attribute is __critical__. You __have__ to give the type the name of the relationship from the Maximo Database Configuration, only in lower case.

Now try running the query in Playground:

```graphql
query {
  po(fromRow:0, numRows:2){
    ponum
    description
    id
    _handle
    poline(fromRow:0, numRows:10){
      polinenum
      description
    }
  }
}
```

## Querying and filtering

GraphQL server for Maximo uses the Maximo QBE for data filtering. We have to define the separate input type for each object we want to query, and declare the searchable fields inside:

```
input POQBE{
  id:ID
  ponum:String
  description:String
  status:String
}
```

By convention, the QBE input type should be named the same as the main type with the QBE appended.
The QBE syntax is the same as in Maximo, and the same restrictions apply, i.e. you can query only the persistent fields, the syntax depend on the field type. In general, if the qbe works in Maximo, it will work in GraphQL Server as well.

To test the qbe, we need to pass the qbe to query. The only way to do it through the Playground is by using the GraphQL _variables_.

Modify the query to look like this:

```graphql
query($qbe:POQBE) {
  po(fromRow:0, numRows:2, qbe:$qbe){
    ponum
    description
    id
    _handle
    status
    poline(fromRow:0, numRows:10){
      polinenum
      description
    }
  }
}
```

Before running the query, specify the variable in the _Query Variables_ section of the Playground:

```graphql
{
  "qbe":{
    "status":"=WAPPR"
  }
}
```

## Pagination and handles


GraphQL server runs Maximo in the background. It keeps the Maximo session open once the user logs in to the GraphQL server. Once you query any object, one of the attributes you can get is the virtual attribute ___handle__. The "__handle__" is the internal id of the MboSet which we had used to fetch the data.
When we need to fetch the next set of data, to do the pagination, we need to pass the ___handle__ parameter as an attribute of the query.

Suppose, you had ran the query, and the returned ___handle__ value is ":1PO". To get the next set of results, you would run the following query:

```graphql
query {
  po(fromRow:10, numRows:10, _handle:":1PO"){
    ponum
    description
    id
    _handle
  }
}
```

Note that the handles are not used for pagination only, you require them for mutations as well (more about that later).

## Value lists

Value lists and domains are one of the crucial features of Maximo, and if you use GraphQL server to implement the application, you will most likely need them.

To specify the value list in the main tyoe, you introduce the type with the name _list_attribute_name_ , where __attribute_name__ is the attribute with the value list. For example:

```graphql
type PO{
  id:ID
  ponum:String
  description:String
  status:String
  orderdate:String
  poline(fromRow:Int,numRows:Int, _handle:String, qbe:POLINEQBE):[POLINE]
  list_status(fromRow:Int,numRows:Int, _handle:String, qbe:ALNDOMAINQBE):[ALNDOMAIN] 
  _handle:String
}
```

Note that you have to define the return type of the value list in the Schema Definition file. The __ALNDOMAIN__ and __SYNONYMDOMAIN__ types are predefined in the system, so you don't have to do it.

Example query:

```graphql
query {
  po(fromRow:0, numRows:1){
    ponum
    description
    id
    _handle
    list_status(fromRow:0, numRows:20){
      value
      description
    }
  }
}
```


# Mutations


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

## Metadata

GraphQL server exposes the information about Maximo object attributes. You can use this to get the default labels, data types and labels. For each type you have to define the conforming Metadata type. For example, for the _PO_ type we will define the _POMETADATA_ type:

```graphql
type POMetadata{
  ponum:ColumnMetadata
  description:ColumnMetadata
  status:ColumnMetadata
  orderdate:ColumnMetadata
}

```

The __ColumnMetadata__ type is pre-defined in the system.
The next step is to refer the metadata type from the main type, using the special ___metadata__ type:

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
  _metadata:POMetadata
}
```
Try the following query in the playground:

```graphql
query {
  po(fromRow:0, numRows:1){
    _metadata{
      description{
		  title
		  remarks
		  persistent
		  domainId
      }
      status{
		  title
		  remarks
		  persistent
		  domainId
      }
      
    }
  }
}

```

# Mutations

Mutations in GraphQL Server offer the same functionality for changing the data as in Maximo - you can _add_, _update_, _delete_ the data on object, route the workflow or run the Mbo or MboSet command.

The aim of GraphQL server is to be completely compatible with Maximo. Maximo system is _transactional_, that means you have to explicitely save the record to store the data in the database. For that purpose we have two built in mutations in GraphQL server - __save__ and __rollback__.

## Principles

There are some general differences between the queries and mutations in any GraphQL system. The  most important ones are:

- You can't nest the mutations, all the changes are done on one level only
- To enter the data inside the mutation, you need the __input__ not the _type_

- In GraphQL Server for Maximo, you have to pass the object handle for each mutation

Lets illustrate this with an example. 

In your GraphQL schema dedinition file, edit the _Mutation_ type, so it contains the upldatePOLINE mutation:

```graphql
type Mutation{
  updatePOLINE(_handle:String,id:ID,data:POLINEInput):POLINE
 }
```

We have to define the input type now:

```graphql
input POLINEInput{
  ponum:String
  description:String
  polinenum:Int
  orderqty:Float
  orderunit:String
  unitcost:Float
  linecost:Float
  _handle:String
}
```

Before running the above mutation, we need to run the GraphQl query to get the ___handle__ and __id__ of the _POLINE_ object we want to update. Use one of the queries from previous examples to get the values.

Now we can run the mutation in Playground:

```graphql
mutation ($polineinput:POLINEInput, $id:ID, $handle:String){
  updatePOLINE(data:$polineinput,id:$id,_handle:$handle ){
    ponum
    polinenum
    description
    id
  }
}

```

Use the results of previous GraphQL query to populate the _id_ and _handle_ Query Variablefs, and then populate any value of the input with test data. Below is the example from my system:

```graphql
{
  "polineinput": {
    "description": "test description"
  },
  "id": "2149016434",
  "handle": ":c"
}
```

If you want to save the results, you need to run the __save__ mutation:

```graphql
mutation{
  save
}
```


## Basic mutations - add, update, delete

As you already must have guessed from the previous chapter, GrapqhQL Server use the name of the mutation to decide what action to perform in the background. The naming convection and signatures for the add, update and delete mutations are:

For add:

```graphql
addOBJECTNAME(_handle:String, data:OBJECTNAMEInput):OBJECTNAME
```

Replace the _OBJECTNAME_ with the name of the object on which you perform the mutation(_POLINE_ in the previous example). As already explained, you need to define the input type

For delete:

```graphql
  deleteOBJECTNAME(_handle:String,id:ID):Boolean
```

For update:

```graphql
 updateOBJECTNAME(_handle:String,id:ID,data:OBJECTNAMEInput):OBJECTNAME
```

Full example for _PO_ and _POLINE_:

```graphql
type Mutation{
  addPO(_handle:String,data:POInput):PO
  updatePO(_handle:String,id:ID,data:POInput):PO
  deletePO(_handle:String,id:ID):Boolean
  addPOLINE(_handle:String,data:POLINEInput):POLINE
  updatePOLINE(_handle:String,id:ID,data:POLINEInput):POLINE
  deletePOLINE(_handle:String,id:ID):Boolean
}
```

### Security
 Every action in GraphQL is contrrolled with the Maximo Signature Security. The privliges are standard Maximo _New_, _Delete_ and _Save_ (the names may vary from application to application).
 
## Command mutations

Command mutations are one of the flahship features of GraphQL Server. Using them, you can run __any__ action defined in Maximo MBOs or MboSets. That means that the full business functionality of Maximo is acessible from the GrapqhQL Server, for example adjust inventory, revise po, change status... No other third party product on the market (except our own MaximoPlus) has this as an option.

Before we go into details let's recap how this is done in Maximo itself. In the vast majority of cases, the action requires the non-peresistent MboSet, that is displayed to user in the dialog. User fills in the data, and Maximo calls the _execute_ method of the non-persistent MboSet. 

When using the command mutations in GraphQL, we need to do the following

- Define the type and input for the non persistent MboSet
- Change the basic type to include the relationship to the non-persistent MboSet
- Add the update mutation on the non-persistent MboSet to the Mutation type
- Add the command mutation on the non-persistent MboSet

Example for the _PO_ change status:

```graphql
type POCHANGESTATUS{
  status:String
  memo:String
  statdate:String
  _handle:String #no id - it is non-persistent
}

input POCHANGESTATUSInput{
  status:String
  memo:String
  statdate:String
}

type PO{
  id:ID
  ponum:String
  description:String
  status:String
  orderdate:String
  poline(fromRow:Int,numRows:Int, _handle:String, qbe:POLINEQBE):[POLINE]
  list_status(fromRow:Int,numRows:Int, _handle:String, qbe:ALNDOMAINQBE):[ALNDOMAIN] 
  _handle:String
  _metadata:POMetadata
  pochangestatus(_handle:String):[POCHANGESTATUS]
}


type Mutation{
  addPO(_handle:String,data:POInput):PO
  updatePO(_handle:String,id:ID,data:POInput):PO
  deletePO(_handle:String,id:ID):Boolean
  addPOLINE(_handle:String,data:POLINEInput):POLINE
  updatePOLINE(_handle:String,id:ID,data:POLINEInput):POLINE
  deletePOLINE(_handle:String,id:ID):Boolean
  updatePOCHANGESTATUS(_handle:String,id:ID,data:POCHANGESTATUSInput):POCHANGESTATUS
  commandPOCHANGESTATUS(_handle:String, id:ID, command:String, isMbo:Boolean):Boolean
 }
```
  
  
  Notice the last line in the _PO_ type definition and last two lines in the _Mutation_ type, this is where we put the required changes to init and change data in _POCHANGESTAUTS_ MboSet. The relationship name is the __pochangestatus__ and the type and object name is __POCHANGESTATUS__. 
  
  First we have to run the query to initialize the non persistent __POCHANGESTATUS__.
  
```graphql
query($poqbe:POQBE){
  po(fromRow:0, numRows:1, qbe:$poqbe){
    ponum
    description
    _handle
    id
    pochangestatus{
      _handle
      memo
      status
      statdate
    }
  }
}
```

Query variable example for above:

```graphql
{
  "poqbe": {
    "status": "=wappr"
  }
}
```

Now run the mutation to update the data in non-persistent set:

```graphql
mutation($handle:String, $pochangestatusinput:POCHANGESTATUSInput){
  updatePOCHANGESTATUS(_handle:$handle, data:$pochangestatusinput){
    status
    memo
    statdate
  }
}
```

The sample query varialbes:

```graphql
{
  "pochangestatusinput": {
    "status": "APPR",
    "memo": "test graphql"
  },
  "handle": ":2"
}
```

Finaly execute the command to actually change the status:

```graphql
mutation($handle:String){
  commandPOCHANGESTATUS(_handle:$handle, command:"execute")
}

```

with the variable:
```graphql
{
  "handle": ":2"
}

```

### Security for the GraphQL commands



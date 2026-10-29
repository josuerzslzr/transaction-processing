rootProject.name = "transaction-processing"

include("transactions-core", "transactions-dynamodb", "transactions-api")

project(":transactions-core").projectDir = file("modules/transactions-core")
project(":transactions-dynamodb").projectDir = file("modules/transactions-dynamodb")
project(":transactions-api").projectDir = file("modules/transactions-api")

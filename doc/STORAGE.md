# Storage

A file system is not always available, or not persistent (e.g. in a VM). Instead, database access
or other APIs must be used. To not bother the code with this information an abstraction should
exist: DocumentStorage.

Java has the FileSystem abstraction. Not sure if that should be used or a dedicated DocumentStorage. TBD.

## What kind data needs to be stored?

- settings files
- logging files
- structured data
- unstructured data

## What FileSystem calls are currently done?

- Files.copy()
- Files.createDirectories()
- Files.createFile()
- Files.createTempFile()
- Files.deleteIfExists()
- Files.exists()
- Files.getLastModifiedTime()
- Files.isDirectory()
- Files.list()
- Files.move()
- Files.readAllBytes()
- Files.readAttributes()
- Files.readString()
- Files.resolve(path)
- Files.size()
- Files.walkFileTree()
- Files.write()
- Files.writeString()

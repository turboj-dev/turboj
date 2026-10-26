# Project Explorer: files, folders and packages

A right click (or Control-click) on an explorer item, Shift+F10, or the context-menu key opens its menu:

| Item | New ▸ | Rename… | Move… | Delete |
| --- | --- | --- | --- | --- |
| Java file (in a source folder) | Java Class, Package, Folder, File | The class, its file and every reference (RefactorJ Rename) | To another package: package declaration and imports follow (RefactorJ Move) | **Safe Delete**: refused while other code uses the class |
| Package (folder in a source folder) | Java Class, Package, Folder, File | Full package name; subpackages follow | Into another package | **Safe Delete**: refused while code outside the package uses its classes |
| Source folder | Java Class, Package, Folder, File | — | — | — |
| Other file or folder | Folder, File | File or folder name | To another folder | Delete |

Every item also offers **Copy Path** and **Reveal in Finder**.

- Rename, Move and Safe Delete keep their usual keys when the explorer has the focus: ⌥⌘R and ⌥⌘⌫ in the TurboJ
  keymap (Move is in the menu); Shift+F6, F6 and ⌘⌦ in IntelliJ's; ⌥⌘R and ⌥⌘V in Eclipse's. ⌫ and ⌦ delete the
  selected item, as in Finder.
- **Undo**: each Rename, Move and Delete is a workspace transaction, and ⌘Z takes it back. A package folder that
  holds no class itself (`com` above `com.example.app` and `com.example.util`) is renamed through each package tree
  below it, one undo step per tree.
- Files that are not UTF-8 text (images, archives) cannot be journaled: a folder containing them is moved directly
  after a confirmation that says Undo cannot take it back, and deleted to the Trash, where Finder's Put Back restores
  it.
- New Package, Folder and File create the item on disk directly; a new file opens in the editor, a new class opens
  the New Java Class form with the package filled in.
- Unsaved files under a folder must be saved before the folder is renamed, moved or deleted; Java files and packages
  go through RefactorJ, which includes unsaved changes.
- The explorer reloads after every operation and after Undo or Redo, keeping open folders open.

Limits: moving a class or package to another source folder (main ↔ test) is not offered; a class file whose name is
not one of its types is renamed and moved as a file. Commands: `explorer.newClass`, `explorer.newPackage`,
`explorer.newFolder`, `explorer.newFile`, `explorer.delete`, `explorer.contextMenu`, and `refactor.rename`,
`refactor.move`, `refactor.safe-delete` acting on the selection when the explorer has the focus.

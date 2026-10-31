# Publish beta.14 from Windows

Download and extract the full beta.14 ZIP, then run from your existing repository:

```powershell
Expand-Archive -Path "$HOME\Downloads\bitchord-morphe-patches-1.1.0-beta.14.zip" -DestinationPath "D:\Bitchord\pkg-114" -Force
cd D:\Bitchord\bitchord-morphe-patches
powershell -ExecutionPolicy Bypass -File "D:\Bitchord\pkg-114\publish-testing.ps1" -Folder "D:\Bitchord\pkg-114"
```

The script validates the package, imports its complete source history, and pushes the testing branch plus a beta.14 prerelease. It does not update main. Read any checksum or branch divergence failure before retrying.

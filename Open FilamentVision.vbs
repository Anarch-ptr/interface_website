Option Explicit

Dim shell, fso, root, runtimeRoot, lockFile, lockStream, lockInfo
Dim command, exitCode, errorFile, errorMessage, stream
Set shell = CreateObject("WScript.Shell")
Set fso = CreateObject("Scripting.FileSystemObject")
root = fso.GetParentFolderName(WScript.ScriptFullName)
runtimeRoot = root & "\.filamentvision-runtime"
lockFile = runtimeRoot & "\launcher.lock"

If Not fso.FolderExists(runtimeRoot) Then fso.CreateFolder runtimeRoot
If fso.FileExists(lockFile) Then
    Set lockInfo = fso.GetFile(lockFile)
    If DateDiff("s", lockInfo.DateLastModified, Now) > 90 Then fso.DeleteFile lockFile, True
End If

On Error Resume Next
Set lockStream = fso.CreateTextFile(lockFile, False)
If Err.Number <> 0 Then
    Err.Clear
    WScript.Quit 0
End If
On Error GoTo 0
lockStream.WriteLine CStr(Now)

command = Chr(34) & root & "\start-website.bat" & Chr(34) & " --hidden"

exitCode = shell.Run(command, 0, True)
If exitCode <> 0 Then
    errorFile = root & "\.filamentvision-runtime\last-error.txt"
    errorMessage = "FilamentVision failed to start."
    If fso.FileExists(errorFile) Then
        Set stream = fso.OpenTextFile(errorFile, 1, False)
        errorMessage = errorMessage & vbCrLf & vbCrLf & stream.ReadAll
        stream.Close
    End If
    MsgBox errorMessage, vbCritical, "FilamentVision"
End If

On Error Resume Next
lockStream.Close
If fso.FileExists(lockFile) Then fso.DeleteFile lockFile, True
On Error GoTo 0

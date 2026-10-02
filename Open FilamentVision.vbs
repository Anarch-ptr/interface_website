Option Explicit

Dim shell, fso, root, command, exitCode, errorFile, errorMessage, stream
Set shell = CreateObject("WScript.Shell")
Set fso = CreateObject("Scripting.FileSystemObject")
root = fso.GetParentFolderName(WScript.ScriptFullName)
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
